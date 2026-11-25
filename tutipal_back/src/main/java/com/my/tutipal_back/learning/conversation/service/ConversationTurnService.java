package com.my.tutipal_back.learning.conversation.service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.my.tutipal_back.ai.dto.AgentDtos;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.learning.conversation.dto.ConversationDtos;
import com.my.tutipal_back.learning.conversation.repository.ConversationStore;
import com.my.tutipal_back.learning.tutor.service.OpenedChat;
import com.my.tutipal_back.learning.tutor.service.TutorService;
import com.my.tutipal_back.sessions.entity.SessionRole;
import com.my.tutipal_back.sessions.service.StudySessionService;

/**
 * PT-106 one turn of a stored conversation, on top of TutorService (which this feature does not change).
 *
 * <ol>
 *   <li>{@link #open} on the request thread: question 1..8000, per-user turn guard (429), ownership (404),
 *       session participant / active / student (404 / 410 / 403), then tutor.openChat (CHAT quota 429).
 *       Every rejection happens before the SSE stream opens and releases the guard.</li>
 *   <li>{@link #run} on the streaming thread: tutor.chat inside the conversation binding, so the Agent context gets
 *       the stored history / summary / memories. The pair is saved only when the answer completed.</li>
 *   <li>{@link #finish} always: releases the quota reservation and the guard, then (if saved) the summary runs
 *       on the same thread, after the response is complete.</li>
 * </ol>
 * No transaction here: a turn can stream for minutes. ConversationService opens short ones.
 */
@Service
public class ConversationTurnService {

    private static final Logger log = LoggerFactory.getLogger(ConversationTurnService.class);
    static final String TURN_IN_PROGRESS = "앞 질문의 답을 아직 만들고 있어요. 끝난 뒤 다시 물어봐 주세요";
    static final String EMPTY_ANSWER = "AI 조교가 답을 만들지 못했어요. 다시 시도해 주세요";

    private final ConversationService conversations;
    private final TutorService tutor;
    private final StudySessionService sessions;
    private final ConversationBinding binding;
    private final ConversationSummarizer summarizer;
    /** Users with a conversation turn in progress (one per user, like TutorService's Agent turn guard). */
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    public ConversationTurnService(ConversationService conversations, TutorService tutor,
                                   StudySessionService sessions, ConversationBinding binding,
                                   ConversationSummarizer summarizer) {
        this.conversations = conversations;
        this.tutor = tutor;
        this.sessions = sessions;
        this.binding = binding;
        this.summarizer = summarizer;
    }

    /** A turn that passed every check and holds a quota reservation and the user's turn slot. */
    public static final class OpenedTurn {

        private final long userId;
        private final long conversationId;
        private final String question;
        private final ConversationService.PreparedTurn prepared;
        private final OpenedChat opened;
        private final AtomicBoolean finished = new AtomicBoolean(false);

        private OpenedTurn(long userId, long conversationId, String question,
                           ConversationService.PreparedTurn prepared, OpenedChat opened) {
            this.userId = userId;
            this.conversationId = conversationId;
            this.question = question;
            this.prepared = prepared;
            this.opened = opened;
        }

        public long conversationId() {
            return conversationId;
        }
    }

    public OpenedTurn open(long userId, long conversationId, String content) {
        String question = content == null ? "" : content.strip();
        if (question.isEmpty() || question.length() > ConversationService.MAX_QUESTION_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "질문은 1~8000자로 써 주세요");
        }
        ConversationService.rejectNul(question, "질문");
        if (!running.add(userId)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, TURN_IN_PROGRESS);
        }
        try {
            ConversationService.PreparedTurn prepared = conversations.prepareTurn(userId, conversationId, question);
            Long sessionId = prepared.request().sessionId();
            if (sessionId != null && sessions.requireActiveRole(userId, sessionId) == SessionRole.TEACHER) {
                throw new ApiException(HttpStatus.FORBIDDEN, "수업 AI 대화는 학생만 쓸 수 있어요");
            }
            OpenedChat opened = tutor.openChat(userId, prepared.request());
            return new OpenedTurn(userId, conversationId, question, prepared, opened);
        } catch (RuntimeException | Error e) {
            running.remove(userId);
            throw e;
        }
    }

    /**
     * Streams the answer (deltas go to onDelta first, then into the stored answer) and saves the pair when it
     * completed. Any failure, stop or disconnect propagates and nothing is saved. A conversation deleted meanwhile
     * ends with {@link ConversationService#DELETED_WHILE_STREAMING} (410).
     */
    public ConversationDtos.Saved run(OpenedTurn turn, Consumer<List<String>> onSources, Consumer<String> onDelta,
                                      Consumer<AgentDtos.AgentEvent> onAgent, Runnable heartbeat) {
        StringBuilder answer = new StringBuilder();
        binding.run(turn.prepared.bound(), () -> tutor.chat(turn.userId, turn.prepared.request(), turn.opened,
                onSources, delta -> {
                    onDelta.accept(delta);
                    append(answer, delta);
                }, onAgent, heartbeat));
        // Stop / disconnect right after the last delta trips nothing inside tutor.chat: check once more before
        // saving, so a stopped turn is never stored (the heartbeat throws when the screen has gone).
        heartbeat.run();
        if (answer.toString().isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, EMPTY_ANSWER);
        }
        ConversationStore.SavedPair pair = conversations.savePair(turn.userId, turn.conversationId, turn.question,
                answer.toString());
        return new ConversationDtos.Saved(String.valueOf(pair.user().id()), String.valueOf(pair.assistant().id()));
    }

    /**
     * Always call once the stream is over (or could not start). Refunds an unused reservation, frees the user's
     * turn slot, then runs the summary if the turn was saved. Never throws.
     */
    public void finish(OpenedTurn turn, boolean saved) {
        if (!turn.finished.compareAndSet(false, true)) {
            return;
        }
        try {
            turn.opened.release();
        } catch (RuntimeException e) {
            log.warn("conversation turn release failed: conversation={} reason={}", turn.conversationId,
                    e.getClass().getSimpleName());
        } finally {
            running.remove(turn.userId);
        }
        if (saved) {
            try {
                summarizer.summarizeIfNeeded(turn.conversationId);
            } catch (RuntimeException e) {
                log.warn("conversation summary failed: conversation={} reason={}", turn.conversationId,
                        e.getClass().getSimpleName());
            }
        }
    }

    /**
     * Keeps at most the stored limit in memory (the rest is still streamed to the screen), never ending on a lone
     * high surrogate. NUL characters are dropped (PostgreSQL text cannot hold them).
     */
    static void append(StringBuilder answer, String delta) {
        int room = ConversationService.MAX_ANSWER_LENGTH - answer.length();
        if (delta == null || room <= 0) {
            return;
        }
        String clean = delta.indexOf('\u0000') >= 0 ? delta.replace("\u0000", "") : delta;
        answer.append(ConversationService.cut(clean, room));
    }
}
