package com.my.tutipal_back.learning.conversation.controller;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.my.tutipal_back.ai.client.AiUnavailableException;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.learning.conversation.dto.ConversationDtos;
import com.my.tutipal_back.learning.conversation.service.ConversationTurnService;

/**
 * PT-106 POST /api/conversations/{id}/messages: continue a stored conversation (SSE).
 *
 * <p>Events: sources {"items"} (if materials were found) → delta {"t"} … → agent (Agent path) → saved
 * {"userMessageId","assistantMessageId"} → done; on failure error {"message"} and nothing is saved.
 * Rejections before the stream (400/403/404/409/410/429) are JSON {status, message} (quota 429 is the shared
 * ProblemDetail). With a clientMessageId already saved in the conversation, the same question is answered from
 * storage (one delta with the stored answer, then saved with the stored ids and done; no model call, no quota) and
 * a different question is 409 JSON. The SSE plumbing (client-gone detection, ping comments) mirrors TutorController, which is owned by
 * PT-113 right now; merge the two after PT-113 lands.
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationTurnController {

    private static final long TIMEOUT_MS = 10 * 60 * 1000L;
    private static final long PING_MS = 15_000L;

    /** The screen disconnected (stop, window closed) or the emitter timed out: stop generating. */
    private static final class ClientGone extends RuntimeException {
        ClientGone() {
            super(null, null, false, false);
        }
    }

    private final ConversationTurnService turns;

    public ConversationTurnController(ConversationTurnService turns) {
        this.turns = turns;
    }

    @PostMapping(value = "/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter send(@AuthenticationPrincipal OAuth2User me, @PathVariable long id,
                           @Valid @RequestBody ConversationDtos.MessageRequest request) {
        long userId = Long.parseLong(me.getName());
        ConversationTurnService.OpenedTurn turn = turns.open(userId, id, request.content(),
                request.clientMessageIdValue());
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        AtomicBoolean gone = new AtomicBoolean(false);
        emitter.onTimeout(() -> gone.set(true));
        emitter.onError(e -> gone.set(true));
        emitter.onCompletion(() -> gone.set(true));
        Runnable heartbeat = () -> {
            if (gone.get()) {
                throw new ClientGone();
            }
        };

        try {
            Thread.ofVirtual().name("conversation-turn").start(() -> {
                boolean saved = false;
                try {
                    ConversationDtos.Saved ids = turns.run(turn,
                            sources -> send(emitter, "sources", Map.of("items", sources)),
                            delta -> {
                                heartbeat.run();
                                send(emitter, "delta", Map.of("t", delta));
                            },
                            event -> send(emitter, "agent", event),
                            heartbeat);
                    saved = true;
                    send(emitter, "saved", ids);
                    send(emitter, "done", Map.of());
                    emitter.complete();
                } catch (ClientGone e) {
                    quietComplete(emitter);
                } catch (ApiException | AiUnavailableException e) {
                    finishWithError(emitter, e.getMessage());
                } catch (RuntimeException e) {
                    finishWithError(emitter, "답변 중 문제가 생겼어요");
                } finally {
                    // after the response completed: frees the turn slot, then the summary may run on this thread
                    turns.finish(turn, saved);
                }
            });
        } catch (RuntimeException | Error e) {
            turns.finish(turn, false);
            throw e;
        }

        Thread.ofVirtual().name("conversation-ping").start(() -> {
            while (!gone.get()) {
                try {
                    Thread.sleep(PING_MS);
                    if (!gone.get()) {
                        emitter.send(SseEmitter.event().comment("ping"));
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (IOException | IllegalStateException e) {
                    gone.set(true);
                }
            }
        });
        return emitter;
    }

    /** Explicit JSON content type: the request asked for text/event-stream. */
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> reject(ApiException error) {
        return json(error.getStatus(), error.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException error) {
        String message = error.getBindingResult().getFieldErrors().stream().findFirst()
                .map(f -> f.getDefaultMessage()).orElse("질문은 1~8000자로 써 주세요");
        return json(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseEntity<Map<String, Object>> json(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("status", status.value(), "message", message));
    }

    private static void finishWithError(SseEmitter emitter, String message) {
        try {
            send(emitter, "error", Map.of("message", message));
            emitter.complete();
        } catch (ClientGone e) {
            quietComplete(emitter);
        }
    }

    private static void quietComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (IllegalStateException ignored) {
            // already finished
        }
    }

    private static void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            throw new ClientGone();
        }
    }
}
