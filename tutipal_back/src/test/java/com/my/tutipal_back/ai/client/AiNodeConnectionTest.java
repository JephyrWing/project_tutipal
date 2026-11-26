package com.my.tutipal_back.ai.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.my.tutipal_back.common.exception.GlobalExceptionHandler;
import com.my.tutipal_back.learning.focus.client.FocusClient;
import com.my.tutipal_back.learning.focus.client.FocusUnavailableException;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

/**
 * PT-110: Spring on EC2 reaches the PC FastAPI through Cloudflare (https://ai.&lt;domain&gt;).
 * <ul>
 *   <li>Every FastAPI client sends the Cloudflare Access service token next to X-Internal-Key, and sends no Access
 *   header when the token is not configured.</li>
 *   <li>PC off / tunnel down (connection refused, Cloudflare 502 / 524 / 530 error 1033 HTML, Access login redirect)
 *   is the normal "AI unavailable" path: AiUnavailableException (503 to the app) or the client's own unavailable
 *   exception, and the Cloudflare HTML body never reaches the app.</li>
 * </ul>
 */
class AiNodeConnectionTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String KEY = "test-internal-key";
    private static final AiAccessHeaders ACCESS = new AiAccessHeaders("test-id.access", "test-access-secret");
    private static final List<AiClient.Message> HI = List.of(new AiClient.Message("user", "hi"));
    private static final FocusClient.Request FOCUS = new FocusClient.Request(
            List.of(new FocusClient.Row(1, "ide", 5, 0)), 12.0, 3);
    /** Shape of the Cloudflare page for "tunnel not connected" (error 1033, HTTP 530). */
    private static final String CLOUDFLARE_1033 = "<!DOCTYPE html><html><head><title>Cloudflare Tunnel error | "
            + "ai.example.test | Cloudflare</title></head><body><h1>Error 1033</h1><p>Cloudflare Tunnel error</p>"
            + "<span>Ray ID: 0123456789abcdef</span></body></html>";

    /** path -> request headers (lower-case name -> first value) of the last request on that path. */
    private final Map<String, Map<String, String>> seen = new ConcurrentHashMap<>();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ---- Access headers on every client ----

    @Test
    void everyClientSendsAccessTokenAndInternalKey() throws IOException {
        String base = fakeFastApi();

        callEveryPath(base, ACCESS);

        assertThat(seen).containsOnlyKeys(paths());
        seen.forEach((path, headers) -> {
            assertThat(headers).as(path).containsEntry("x-internal-key", KEY);
            assertThat(headers).as(path).containsEntry("cf-access-client-id", "test-id.access");
            assertThat(headers).as(path).containsEntry("cf-access-client-secret", "test-access-secret");
        });
    }

    @Test
    void noAccessHeaderWhenTokenIsNotConfigured() throws IOException {
        String base = fakeFastApi();

        callEveryPath(base, AiAccessHeaders.none());

        assertThat(seen).containsOnlyKeys(paths());
        seen.forEach((path, headers) -> {
            assertThat(headers).as(path).containsEntry("x-internal-key", KEY);
            assertThat(headers).as(path).doesNotContainKeys("cf-access-client-id", "cf-access-client-secret");
        });
    }

    /**
     * Guard: a new FastAPI client must use AiAccessHeaders too. Every main source file that sends X-Internal-Key
     * also references AiAccessHeaders.
     */
    @Test
    void everySourceThatSendsTheInternalKeyAlsoSendsTheAccessToken() throws IOException {
        Path main = Path.of("src", "main", "java");
        List<Path> senders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(main)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (source.contains("\"X-Internal-Key\"")) {
                    senders.add(file);
                    assertThat(source).as(file.toString()).contains("AiAccessHeaders");
                }
            }
        }
        assertThat(senders).extracting(p -> p.getFileName().toString())
                .contains("AiClient.java", "ClusterClient.java", "RerankClient.java", "FocusClient.java");
    }

    // ---- PC off / tunnel down ----

    @ParameterizedTest(name = "Cloudflare status {0}")
    @ValueSource(ints = {502, 503, 504, 524, 530})
    void cloudflareErrorPageIsAiUnavailableWithoutTheHtml(int status) throws IOException {
        String base = errorPage(status, CLOUDFLARE_1033);
        AiClient ai = new AiClient(base, KEY, ACCESS, JSON);

        for (ThrowingCallable call : List.<ThrowingCallable>of(
                () -> ai.chatStream("s", HI, d -> { }),
                () -> ai.complete("s", HI, true),
                () -> ai.embedWithModel(List.of("a")),
                () -> ai.summarize(null, List.of(), null))) {
            assertThatThrownBy(call).isInstanceOfSatisfying(AiUnavailableException.class, e -> {
                assertThat(e.getMessage()).isEqualTo("AI 조교에 연결할 수 없어요");
                assertThat(e.isAfterOutput()).isFalse();
                // a Cloudflare page is not FastAPI's confirmed embedding outage marker: no automatic retry,
                // the material becomes FAILED + retryable (PT-99 manual reprocess)
                assertThat(e.isTemporaryEmbeddingFailure()).isFalse();
            });
        }
        assertThatThrownBy(() -> new ClusterClient(base, KEY, ACCESS, JSON).cluster(List.of(vector()), List.of()))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessage("AI 조교에 연결할 수 없어요");
        assertThatThrownBy(() -> new RerankClient(base, KEY, ACCESS, Duration.ofSeconds(2)).rerank("q", List.of("a")))
                .isInstanceOfSatisfying(RerankUnavailableException.class, e -> assertThat(e.reason())
                        .isEqualTo("status"));
        assertThatThrownBy(() -> new FocusClient(base, KEY, ACCESS, Duration.ofSeconds(2)).predict(FOCUS))
                .isInstanceOf(FocusUnavailableException.class)
                .hasMessage("status");
    }

    /** Missing or wrong service token: Access answers with a redirect to its login page instead of FastAPI. */
    @Test
    void accessLoginRedirectIsAiUnavailable() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location", "https://example.cloudflareaccess.com/cdn-cgi/access/login");
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            byte[] bytes = "<html>login</html>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(302, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        AiClient ai = new AiClient(base, KEY, AiAccessHeaders.none(), JSON);

        assertThatThrownBy(() -> ai.chatStream("s", HI, d -> { })).isInstanceOf(AiUnavailableException.class);
        assertThatThrownBy(() -> ai.embedWithModel(List.of("a"))).isInstanceOf(AiUnavailableException.class);
        assertThatThrownBy(() -> ai.summarize(null, List.of(), null)).isInstanceOf(AiUnavailableException.class);
        assertThatThrownBy(() -> new ClusterClient(base, KEY, AiAccessHeaders.none(), JSON)
                .cluster(List.of(vector()), List.of())).isInstanceOf(AiUnavailableException.class);
        assertThatThrownBy(() -> new RerankClient(base, KEY, AiAccessHeaders.none(), Duration.ofSeconds(2))
                .rerank("q", List.of("a"))).isInstanceOf(RerankUnavailableException.class);
        assertThatThrownBy(() -> new FocusClient(base, KEY, AiAccessHeaders.none(), Duration.ofSeconds(2))
                .predict(FOCUS)).isInstanceOf(FocusUnavailableException.class);
    }

    @Test
    void connectionRefusedIsAiUnavailableOnEveryClient() throws IOException {
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        String base = "http://127.0.0.1:" + port;
        AiClient ai = new AiClient(base, KEY, ACCESS, JSON);

        assertThatThrownBy(() -> ai.chatStream("s", HI, d -> { }))
                .isInstanceOfSatisfying(AiUnavailableException.class, e -> assertThat(e.isAfterOutput()).isFalse());
        assertThatThrownBy(() -> ai.summarize(null, List.of(), null)).isInstanceOf(AiUnavailableException.class);
        assertThatThrownBy(() -> new ClusterClient(base, KEY, ACCESS, JSON).cluster(List.of(vector()), List.of()))
                .isInstanceOf(AiUnavailableException.class);
        assertThatThrownBy(() -> new RerankClient(base, KEY, ACCESS, Duration.ofSeconds(2)).rerank("q", List.of("a")))
                .isInstanceOfSatisfying(RerankUnavailableException.class, e -> assertThat(e.reason())
                        .isEqualTo("connect"));
        assertThatThrownBy(() -> new FocusClient(base, KEY, ACCESS, Duration.ofSeconds(2)).predict(FOCUS))
                .isInstanceOf(FocusUnavailableException.class)
                .hasMessage("connect");
    }

    /** End to end in Spring MVC: Cloudflare 530 page from the tunnel -> 503 {status, message}, no HTML. */
    @Test
    void appGets503WithFixedMessageAndNoCloudflareBody() throws Exception {
        AiClient ai = new AiClient(errorPage(530, CLOUDFLARE_1033), KEY, ACCESS, JSON);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeController(ai))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        String body = mvc.perform(get("/probe"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").value("AI 조교에 연결할 수 없어요"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("Cloudflare").doesNotContain("1033").doesNotContain("Ray ID")
                .doesNotContain("<html");
    }

    @RestController
    static class ProbeController {

        private final AiClient ai;

        ProbeController(AiClient ai) {
            this.ai = ai;
        }

        @GetMapping("/probe")
        String probe() {
            return ai.complete("s", HI, false);
        }
    }

    // ---- helpers ----

    private static List<String> paths() {
        return List.of("/v1/chat", "/v1/embed", "/v1/insights/cluster", "/v1/rerank", "/v1/focus/predict");
    }

    private void callEveryPath(String base, AiAccessHeaders access) {
        AiClient ai = new AiClient(base, KEY, access, JSON);
        assertThat(ai.complete("s", HI, false)).isEqualTo("a");
        assertThat(ai.embedWithModel(List.of("a")).vectors()).hasSize(1);
        assertThat(new ClusterClient(base, KEY, access, JSON).cluster(List.of(vector()), List.of()).status())
                .isEqualTo("OK");
        assertThat(new RerankClient(base, KEY, access, Duration.ofSeconds(2)).rerank("q", List.of("a")))
                .containsExactly(0.5);
        assertThat(new FocusClient(base, KEY, access, Duration.ofSeconds(2)).predict(FOCUS).modelVersion()).isNull();
    }

    /** Minimal FastAPI stand-in that records the request headers per path. */
    private String fakeFastApi() throws IOException {
        float[] v = vector();
        StringBuilder vectorJson = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            vectorJson.append(i == 0 ? "" : ",").append(v[i]);
        }
        vectorJson.append(']');
        Map<String, String> answers = Map.of(
                "/v1/chat", "{\"delta\":\"a\"}\n",
                "/v1/embed", "{\"model\":\"ollama:bge-m3:1024\",\"vectors\":[" + vectorJson + "]}",
                "/v1/insights/cluster", "{\"status\":\"OK\",\"algorithm\":\"kmeans-silhouette-v1\",\"k\":1,"
                        + "\"silhouette\":null,\"clusters\":[{\"members\":[0],\"concept\":null}]}",
                "/v1/rerank", "{\"scores\":[0.5]}",
                "/v1/focus/predict", "{\"status\":\"insufficient_data\"}");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            Map<String, String> headers = new ConcurrentHashMap<>();
            exchange.getRequestHeaders().forEach((name, values) ->
                    headers.put(name.toLowerCase(Locale.ROOT), values.get(0)));
            seen.put(path, headers);
            exchange.getRequestBody().readAllBytes();
            String answer = answers.get(path);
            byte[] bytes = (answer == null ? "{}" : answer).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type",
                    path.equals("/v1/chat") ? "application/x-ndjson" : "application/json");
            exchange.sendResponseHeaders(answer == null ? 404 : 200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Every path answers like the Cloudflare edge when the tunnel or origin is down. */
    private String errorPage(int status, String html) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            exchange.getResponseHeaders().add("Server", "cloudflare");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static float[] vector() {
        float[] v = new float[1024];
        Arrays.fill(v, 0.01f);
        return v;
    }
}
