package com.my.tutipal_back.ai.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PT-86: Spring -> FastAPI POST /v1/rerank against a real local HTTP server.
 * Every failure (status, body, size, non-finite score, timeout, connection) is a RerankUnavailableException
 * so the search service can fall back to the cosine order.
 */
class RerankClientTest {

    private static final String KEY = "test-key";
    private static final List<String> DOCS = List.of("첫 번째 조각", "두 번째 조각", "세 번째 조각");

    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> key = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String serving(int status, String responseBody, long delayMillis) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            key.set(exchange.getRequestHeaders().getFirst("X-Internal-Key"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                }
            } catch (IOException ignored) {
                // client gave up (timeout test)
            }
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private String serving(int status, String responseBody) throws IOException {
        return serving(status, responseBody, 0);
    }

    private static RerankClient client(String baseUrl) {
        return new RerankClient(baseUrl, KEY, AiAccessHeaders.none(), Duration.ofSeconds(2));
    }

    @Test
    void postsQueryAndDocumentsToRerankWithInternalKey() throws IOException {
        String base = serving(200, "{\"model\":\"fake-reranker\",\"scores\":[0.1,0.9,0.5]}");

        client(base).rerank("정렬이 뭐야?", DOCS);

        assertThat(method.get()).isEqualTo("POST");
        assertThat(path.get()).isEqualTo("/v1/rerank");
        assertThat(key.get()).isEqualTo(KEY);
        assertThat(contentType.get()).startsWith("application/json");
        JsonNode sent = json.readTree(body.get());
        assertThat(sent.get("query").asString()).isEqualTo("정렬이 뭐야?");
        assertThat(sent.get("documents").size()).isEqualTo(3);
        assertThat(sent.get("documents").get(0).asString()).isEqualTo("첫 번째 조각");
        assertThat(sent.get("documents").get(2).asString()).isEqualTo("세 번째 조각");
        assertThat(sent.propertyNames()).containsExactlyInAnyOrder("query", "documents");
    }

    @Test
    void returnsScoresInInputOrder() throws IOException {
        String base = serving(200, "{\"model\":\"fake-reranker\",\"scores\":[0.1,-2.5,7.25]}");

        double[] scores = client(base).rerank("q", DOCS);

        assertThat(scores).containsExactly(0.1, -2.5, 7.25);
    }

    @Test
    void isConfiguredWhenBaseUrlPresent() throws IOException {
        assertThat(client(serving(200, "{}")).isConfigured()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankBaseUrlIsNotConfiguredAndRerankThrowsWithoutNetwork(String blank) {
        RerankClient client = new RerankClient(blank, KEY, AiAccessHeaders.none(), Duration.ofSeconds(2));

        assertThat(client.isConfigured()).isFalse();
        assertThatThrownBy(() -> client.rerank("q", DOCS)).isInstanceOf(RerankUnavailableException.class);
    }

    @Test
    void nullBaseUrlIsNotConfigured() {
        RerankClient client = new RerankClient(null, KEY, AiAccessHeaders.none(), Duration.ofSeconds(2));

        assertThat(client.isConfigured()).isFalse();
        assertThatThrownBy(() -> client.rerank("q", DOCS)).isInstanceOf(RerankUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 422, 500, 503})
    void errorStatusIsUnavailable(int status) throws IOException {
        String base = serving(status, "{\"detail\":\"rerank unavailable\"}");

        assertThatThrownBy(() -> client(base).rerank("q", DOCS)).isInstanceOf(RerankUnavailableException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not json",
            "",
            "{}",
            "{\"model\":\"m\"}",
            "{\"model\":\"m\",\"scores\":null}",
            "{\"model\":\"m\",\"scores\":\"0.1,0.2,0.3\"}",
            "{\"model\":\"m\",\"scores\":[0.1,null,0.3]}",
            "{\"model\":\"m\",\"scores\":[0.1,\"abc\",0.3]}"
    })
    void malformedBodyIsUnavailable(String responseBody) throws IOException {
        String base = serving(200, responseBody);

        assertThatThrownBy(() -> client(base).rerank("q", DOCS)).isInstanceOf(RerankUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"model\":\"m\",\"scores\":[0.1,0.2]}",
            "{\"model\":\"m\",\"scores\":[0.1,0.2,0.3,0.4]}",
            "{\"model\":\"m\",\"scores\":[]}"
    })
    void scoresLengthMismatchIsUnavailable(String responseBody) throws IOException {
        String base = serving(200, responseBody);

        assertThatThrownBy(() -> client(base).rerank("q", DOCS)).isInstanceOf(RerankUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"model\":\"m\",\"scores\":[0.1,\"NaN\",0.3]}",
            "{\"model\":\"m\",\"scores\":[0.1,1e999,0.3]}",
            "{\"model\":\"m\",\"scores\":[0.1,-1e999,0.3]}",
            "{\"model\":\"m\",\"scores\":[0.1,\"Infinity\",0.3]}"
    })
    void nonFiniteScoreIsUnavailable(String responseBody) throws IOException {
        String base = serving(200, responseBody);

        assertThatThrownBy(() -> client(base).rerank("q", DOCS)).isInstanceOf(RerankUnavailableException.class);
    }

    @Test
    void slowServerTimesOutWithinConfiguredTimeout() throws IOException {
        String base = serving(200, "{\"model\":\"m\",\"scores\":[0.1,0.2,0.3]}", 3_000);
        RerankClient client = new RerankClient(base, KEY, AiAccessHeaders.none(), Duration.ofMillis(300));

        long started = System.nanoTime();
        assertThatThrownBy(() -> client.rerank("q", DOCS)).isInstanceOf(RerankUnavailableException.class);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(elapsedMillis).isLessThan(2_000);
    }

    @Test
    void connectionRefusedIsUnavailable() throws IOException {
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }

        assertThatThrownBy(() -> client("http://127.0.0.1:" + port).rerank("q", DOCS))
                .isInstanceOf(RerankUnavailableException.class);
    }

    @Test
    void unavailableExceptionMessageDoesNotLeakQueryOrDocuments() throws IOException {
        String base = serving(500, "{\"detail\":\"boom\"}");

        assertThatThrownBy(() -> client(base).rerank("비밀 질문", List.of("비밀 조각")))
                .isInstanceOf(RerankUnavailableException.class)
                .satisfies(e -> assertThat(String.valueOf(e.getMessage())).doesNotContain("비밀"));
    }
}
