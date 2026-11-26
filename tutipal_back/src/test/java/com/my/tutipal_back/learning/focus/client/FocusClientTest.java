package com.my.tutipal_back.learning.focus.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.my.tutipal_back.ai.client.AiAccessHeaders;
import com.my.tutipal_back.learning.focus.FocusState;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PT-90 AC-6: Spring -> FastAPI POST /v1/focus/predict against a real local HTTP server. Only rows
 * (minutes_ago, category, idle_sec, switch_count), hour and weekday are sent. ok maps to FOCUS / DISTRACTED / BREAK,
 * insufficient_data to PENDING; every failure (status, timeout, malformed or oversized body) is a
 * FocusUnavailableException so the service can answer UNKNOWN.
 */
class FocusClientTest {

    private static final String KEY = "test-key";
    private static final FocusClient.Request REQUEST = new FocusClient.Request(List.of(
            new FocusClient.Row(2, "browser", 60, 3),
            new FocusClient.Row(1, "ide", 5, 0)), 12.25, 3);

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

    private static FocusClient client(String baseUrl) {
        return new FocusClient(baseUrl, KEY, AiAccessHeaders.none(), Duration.ofSeconds(2));
    }

    private static String ok(String state) {
        return "{\"status\":\"ok\",\"state\":\"" + state + "\",\"probabilities\":{\"break\":0.1,\"distracted\":0.2,"
                + "\"focus\":0.7},\"model_version\":\"focus-lgbm-1\"}";
    }

    @Test
    void postsOnlyRowsHourAndWeekdayWithInternalKey() throws IOException {
        String base = serving(200, ok("focus"));

        client(base).predict(REQUEST);

        assertThat(method.get()).isEqualTo("POST");
        assertThat(path.get()).isEqualTo("/v1/focus/predict");
        assertThat(key.get()).isEqualTo(KEY);
        assertThat(contentType.get()).startsWith("application/json");
        JsonNode sent = json.readTree(body.get());
        assertThat(sent.propertyNames()).containsExactlyInAnyOrder("rows", "hour", "weekday");
        assertThat(sent.get("hour").asDouble()).isEqualTo(12.25);
        assertThat(sent.get("weekday").asInt()).isEqualTo(3);
        assertThat(sent.get("rows").size()).isEqualTo(2);
        JsonNode first = sent.get("rows").get(0);
        assertThat(first.propertyNames()).containsExactlyInAnyOrder("minutes_ago", "category", "idle_sec", "switch_count");
        assertThat(first.get("minutes_ago").asInt()).isEqualTo(2);
        assertThat(first.get("category").asString()).isEqualTo("browser");
        assertThat(first.get("idle_sec").asInt()).isEqualTo(60);
        assertThat(first.get("switch_count").asInt()).isEqualTo(3);
        assertThat(body.get()).doesNotContain("exe").doesNotContain("user").doesNotContain("session");
    }

    @Test
    void okFocusIsFocusWithModelVersion() throws IOException {
        FocusClient.Prediction prediction = client(serving(200, ok("focus"))).predict(REQUEST);

        assertThat(prediction.state()).isEqualTo(FocusState.FOCUS);
        assertThat(prediction.modelVersion()).isEqualTo("focus-lgbm-1");
    }

    @Test
    void okDistractedAndBreakMap() throws IOException {
        assertThat(client(serving(200, ok("distracted"))).predict(REQUEST).state()).isEqualTo(FocusState.DISTRACTED);
        server.stop(0);
        assertThat(client(serving(200, ok("break"))).predict(REQUEST).state()).isEqualTo(FocusState.BREAK);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"status\":\"insufficient_data\"}",
            "{\"status\":\"insufficient_data\",\"state\":null,\"probabilities\":null,\"model_version\":null}",
    })
    void insufficientDataIsPending(String response) throws IOException {
        FocusClient.Prediction prediction = client(serving(200, response)).predict(REQUEST);

        assertThat(prediction.state()).isEqualTo(FocusState.PENDING);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 413, 422, 500, 503})
    void errorStatusIsUnavailable(int status) throws IOException {
        String base = serving(status, "{\"detail\":\"focus model unavailable\"}");

        assertThatThrownBy(() -> client(base).predict(REQUEST)).isInstanceOf(FocusUnavailableException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "not json",
            "[]",
            "{}",
            "{\"status\":\"maybe\"}",
            "{\"status\":\"ok\"}",
            "{\"status\":\"ok\",\"state\":\"sleepy\",\"model_version\":\"v\"}",
            "{\"status\":\"ok\",\"state\":\"FOCUS\",\"model_version\":\"v\"}",
            "{\"status\":\"ok\",\"state\":1,\"model_version\":\"v\"}",
    })
    void malformedBodyIsUnavailable(String response) throws IOException {
        String base = serving(200, response);

        assertThatThrownBy(() -> client(base).predict(REQUEST)).isInstanceOf(FocusUnavailableException.class);
    }

    @Test
    void oversizedBodyIsUnavailable() throws IOException {
        String big = "{\"status\":\"ok\",\"state\":\"focus\",\"model_version\":\"" + "v".repeat(9000) + "\"}";
        String base = serving(200, big);

        assertThatThrownBy(() -> client(base).predict(REQUEST)).isInstanceOf(FocusUnavailableException.class);
    }

    @Test
    void slowServerIsUnavailableWithinTheTimeout() throws IOException {
        String base = serving(200, ok("focus"), 1500);
        FocusClient client = new FocusClient(base, KEY, AiAccessHeaders.none(), Duration.ofMillis(300));

        long started = System.nanoTime();
        assertThatThrownBy(() -> client.predict(REQUEST)).isInstanceOf(FocusUnavailableException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1200));
    }

    @Test
    void unreachableServerIsUnavailable() throws IOException {
        String base = serving(200, ok("focus"));
        server.stop(0);
        server = null;

        assertThatThrownBy(() -> client(base).predict(REQUEST)).isInstanceOf(FocusUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankBaseUrlIsUnavailableWithoutNetwork(String blank) {
        FocusClient client = new FocusClient(blank, KEY, AiAccessHeaders.none(), Duration.ofSeconds(2));

        assertThatThrownBy(() -> client.predict(REQUEST)).isInstanceOf(FocusUnavailableException.class);
    }

    @Test
    void trailingSlashInBaseUrlIsHandled() throws IOException {
        String base = serving(200, ok("focus"));

        client(base + "/").predict(REQUEST);

        assertThat(path.get()).isEqualTo("/v1/focus/predict");
    }
}
