package com.my.tutipal_back.ai.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.my.tutipal_back.ai.dto.AgentDtos;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PT-106 AC-23: AiClient.summarize calls POST /v1/agent/summarize (contract 6.5) with the internal key.
 * 2xx → summary text; non-2xx, broken body or connection failure → AiUnavailableException; bodies never logged.
 */
@ExtendWith(OutputCaptureExtension.class)
class AiClientSummarizeTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<AgentDtos.ContextMessage> MESSAGES = List.of(
            new AgentDtos.ContextMessage("user", "SECRET-INPUT range(5)는 뭐예요?"),
            new AgentDtos.ContextMessage("assistant", "0부터 4까지예요"));

    private HttpServer server;
    private final AtomicReference<String> sentBody = new AtomicReference<>();
    private final AtomicReference<String> sentKey = new AtomicReference<>();
    private final AtomicReference<String> sentMethod = new AtomicReference<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private AiClient serving(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/agent/summarize", exchange -> {
            sentMethod.set(exchange.getRequestMethod());
            sentBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            sentKey.set(exchange.getRequestHeaders().getFirst("X-Internal-Key"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
        return new AiClient("http://127.0.0.1:" + server.getAddress().getPort(), "test-key", AiAccessHeaders.none(),
                JSON);
    }

    @Test
    void okReturnsTheSummaryAndSendsTheContractBody() throws IOException {
        AiClient ai = serving(200, "{\"summary\":\"새 요약\"}");

        String summary = ai.summarize(AgentDtos.Scope.session(10L), MESSAGES, "이전 요약");

        assertThat(summary).isEqualTo("새 요약");
        assertThat(sentMethod.get()).isEqualTo("POST");
        assertThat(sentKey.get()).isEqualTo("test-key");
        JsonNode body = JSON.readTree(sentBody.get());
        assertThat(body.get("scope").get("kind").asString()).isEqualTo("session");
        assertThat(body.get("scope").get("sessionId").asLong()).isEqualTo(10L);
        assertThat(body.get("messages").size()).isEqualTo(2);
        assertThat(body.get("messages").get(0).get("role").asString()).isEqualTo("user");
        assertThat(body.get("messages").get(0).get("content").asString()).isEqualTo(MESSAGES.getFirst().content());
        assertThat(body.get("previousSummary").asString()).isEqualTo("이전 요약");
        List<String> fields = body.properties().stream().map(java.util.Map.Entry::getKey).toList();
        assertThat(fields).containsExactlyInAnyOrder("scope", "messages", "previousSummary");
    }

    @Test
    void personalScopeWithoutPreviousSummary() throws IOException {
        AiClient ai = serving(200, "{\"summary\":\"첫 요약\"}");

        assertThat(ai.summarize(AgentDtos.Scope.personal(), MESSAGES, null)).isEqualTo("첫 요약");

        JsonNode body = JSON.readTree(sentBody.get());
        assertThat(body.get("scope").get("kind").asString()).isEqualTo("personal");
        assertThat(body.get("previousSummary") == null || body.get("previousSummary").isNull()).isTrue();
    }

    @Test
    void emptySummaryErrorIsUnavailable() throws IOException {
        AiClient ai = serving(502, "{\"detail\":\"empty_summary\"}");

        assertThatThrownBy(() -> ai.summarize(AgentDtos.Scope.personal(), MESSAGES, null))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void llmUnavailableIsUnavailable() throws IOException {
        AiClient ai = serving(503, "{\"detail\":\"llm_unavailable\"}");

        assertThatThrownBy(() -> ai.summarize(AgentDtos.Scope.personal(), MESSAGES, null))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void validationRejectionIsUnavailable() throws IOException {
        AiClient ai = serving(422, "{\"detail\":[{\"msg\":\"SECRET-DETAIL\"}]}");

        assertThatThrownBy(() -> ai.summarize(AgentDtos.Scope.personal(), MESSAGES, null))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessageNotContaining("SECRET");
    }

    @Test
    void okWithBrokenOrBlankBodyIsUnavailable() throws IOException {
        for (String body : List.of("not json", "{}", "{\"summary\":\"   \"}", "{\"summary\":null}")) {
            AiClient ai = serving(200, body);
            assertThatThrownBy(() -> ai.summarize(AgentDtos.Scope.personal(), MESSAGES, null))
                    .as(body).isInstanceOf(AiUnavailableException.class);
            stop();
        }
        server = null;
    }

    @Test
    void connectionRefusedIsUnavailable() throws IOException {
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        AiClient ai = new AiClient("http://127.0.0.1:" + port, "test-key", AiAccessHeaders.none(), JSON);

        assertThatThrownBy(() -> ai.summarize(AgentDtos.Scope.personal(), MESSAGES, null))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void neitherRequestNorResponseBodyIsLogged(CapturedOutput output) throws IOException {
        AiClient ai = serving(500, "{\"detail\":\"SECRET-RESPONSE\"}");

        assertThatThrownBy(() -> ai.summarize(AgentDtos.Scope.personal(), MESSAGES, "SECRET-PREVIOUS"))
                .isInstanceOf(AiUnavailableException.class);

        assertThat(output.getAll()).doesNotContain("SECRET-INPUT", "SECRET-PREVIOUS", "SECRET-RESPONSE");
    }
}
