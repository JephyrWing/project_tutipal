package com.my.tutipal_back.ai.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.ai.client.AiSlots;
import com.my.tutipal_back.ai.client.AiUnavailableException;
import com.my.tutipal_back.learning.tutor.dto.ChatRequest;
import com.my.tutipal_back.learning.tutor.service.TutorService;
import com.my.tutipal_back.rag.service.RagSearchService;
import com.my.tutipal_back.sessions.service.StudySessionService;
import com.my.tutipal_back.learning.tutor.service.StudentQuestionSink;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

/**
 * PT-97 regression with a real AiClient stream (only the AI server is faked over HTTP):
 * client cancel and AI failure after output are counted, AI failure before output is refunded.
 */
class AiQuotaRealClientTest {

    private static final long USER = 9L;
    private static final ChatRequest REQ = new ChatRequest("math", List.of(new ChatRequest.Message("user", "hi")), null);

    private final AiQuota quota = AiQuotaFixtures.quota(1, Duration.ofHours(1),
            new MutableClock(Instant.parse("2026-11-25T00:00:00Z")));
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private TutorService tutorAgainst(String... lines) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (String line : lines) {
                    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (IOException ignored) {
                // client closed the stream
            }
        });
        server.start();
        AiClient ai = new AiClient("http://127.0.0.1:" + server.getAddress().getPort(), "k", JsonMapper.builder().build());
        return new TutorService(ai, mock(StudySessionService.class), mock(RagSearchService.class),
                new AiSlots(2, Duration.ofSeconds(1)), quota, null, null, false,
                StudentQuestionSink.NONE);
    }

    @Test
    void cancelAfterFirstDeltaIsCountedNotWrappedAsAiUnavailable() throws IOException {
        TutorService tutor = tutorAgainst("{\"delta\":\"a\"}", "{\"delta\":\"b\"}", "{\"delta\":\"c\"}");
        var gone = new IllegalStateException("client gone");

        assertThatThrownBy(() -> tutor.chat(USER, REQ, tutor.openChat(USER, REQ), s -> { }, d -> { throw gone; }))
                .isSameAs(gone);

        assertThat(AiQuotaFixtures.hasRoom(quota, USER, AiFeature.CHAT)).isFalse();
    }

    @Test
    void aiTimeoutAfterStreamedOutputIsCounted() throws IOException {
        TutorService tutor = tutorAgainst("{\"delta\":\"a\"}", "{\"delta\":\"b\"}", "{\"error\":\"llm_unavailable\"}");

        assertThatThrownBy(() -> tutor.chat(USER, REQ, tutor.openChat(USER, REQ), s -> { }, d -> { }))
                .isInstanceOf(AiUnavailableException.class);

        assertThat(AiQuotaFixtures.hasRoom(quota, USER, AiFeature.CHAT)).isFalse();
    }

    @Test
    void aiDownBeforeAnyOutputIsRefunded() throws IOException {
        TutorService tutor = tutorAgainst("{\"error\":\"llm_unavailable\"}");

        assertThatThrownBy(() -> tutor.chat(USER, REQ, tutor.openChat(USER, REQ), s -> { }, d -> { }))
                .isInstanceOf(AiUnavailableException.class);

        assertThat(AiQuotaFixtures.hasRoom(quota, USER, AiFeature.CHAT)).isTrue();
    }
}
