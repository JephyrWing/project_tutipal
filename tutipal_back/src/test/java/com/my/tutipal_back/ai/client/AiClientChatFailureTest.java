package com.my.tutipal_back.ai.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

/**
 * PT-97: AiClient tells "failed before the model produced output" (AI server down) apart from
 * "failed after output started" (model already used the GPU), against a real local HTTP server.
 */
class AiClientChatFailureTest {

    private static final List<AiClient.Message> HI = List.of(new AiClient.Message("user", "hi"));

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private AiClient serving(int status, String... lines) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(status, 0);
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
        return client("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static AiClient client(String baseUrl) {
        return new AiClient(baseUrl, "test-key", AiAccessHeaders.none(), JsonMapper.builder().build());
    }

    @Test
    void errorLineBeforeAnyDeltaIsUnavailableBeforeOutput() throws IOException {
        AiClient ai = serving(200, "{\"error\":\"llm_unavailable\"}");

        assertThatThrownBy(() -> ai.chatStream("s", HI, d -> { }))
                .isInstanceOfSatisfying(AiUnavailableException.class, e -> assertThat(e.isAfterOutput()).isFalse());
    }

    @Test
    void errorLineAfterDeltaIsUnavailableAfterOutput() throws IOException {
        AiClient ai = serving(200, "{\"delta\":\"a\"}", "{\"delta\":\"b\"}", "{\"error\":\"llm_unavailable\"}");
        List<String> got = new ArrayList<>();

        assertThatThrownBy(() -> ai.chatStream("s", HI, got::add))
                .isInstanceOfSatisfying(AiUnavailableException.class, e -> assertThat(e.isAfterOutput()).isTrue());
        assertThat(got).containsExactly("a", "b");
    }

    @Test
    void completeAfterPartialOutputIsUnavailableAfterOutput() throws IOException {
        AiClient ai = serving(200, "{\"delta\":\"{\\\"q\\\":\"}", "{\"error\":\"llm_unavailable\"}");

        assertThatThrownBy(() -> ai.complete("s", HI, true))
                .isInstanceOfSatisfying(AiUnavailableException.class, e -> assertThat(e.isAfterOutput()).isTrue());
    }

    @Test
    void brokenLineAfterDeltaIsUnavailableAfterOutput() throws IOException {
        AiClient ai = serving(200, "{\"delta\":\"a\"}", "not json");

        assertThatThrownBy(() -> ai.complete("s", HI, false))
                .isInstanceOfSatisfying(AiUnavailableException.class, e -> assertThat(e.isAfterOutput()).isTrue());
    }

    @Test
    void errorStatusIsUnavailableBeforeOutput() throws IOException {
        AiClient ai = serving(500);

        assertThatThrownBy(() -> ai.complete("s", HI, false))
                .isInstanceOfSatisfying(AiUnavailableException.class, e -> assertThat(e.isAfterOutput()).isFalse());
    }

    @Test
    void connectionRefusedIsUnavailableBeforeOutput() throws IOException {
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        AiClient ai = client("http://127.0.0.1:" + port);

        assertThatThrownBy(() -> ai.chatStream("s", HI, d -> { }))
                .isInstanceOfSatisfying(AiUnavailableException.class, e -> assertThat(e.isAfterOutput()).isFalse());
    }

    @Test
    void exceptionFromOnDeltaPropagatesUnchanged() throws IOException {
        AiClient ai = serving(200, "{\"delta\":\"a\"}", "{\"delta\":\"b\"}");
        var gone = new IllegalStateException("client gone");

        assertThatThrownBy(() -> ai.chatStream("s", HI, d -> { throw gone; })).isSameAs(gone);
    }

    @Test
    void fullStreamCompletesNormally() throws IOException {
        AiClient ai = serving(200, "{\"delta\":\"a\"}", "", "{\"delta\":\"b\"}");

        assertThat(ai.complete("s", HI, false)).isEqualTo("ab");
    }
}
