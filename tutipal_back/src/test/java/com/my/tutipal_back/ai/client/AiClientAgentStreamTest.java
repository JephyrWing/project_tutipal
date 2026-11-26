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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.my.tutipal_back.ai.dto.AgentDtos;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PT-85 contract 7.5 / 7.6: AiClient.agentStream reads the /v1/agent/chat NDJSON lines.
 * emitted (no refund) = a delta or a variant line was received; ping / start / need_search are not output.
 */
class AiClientAgentStreamTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String META = "{\"meta\":{\"agent\":\"student\",\"route\":\"answer_request\","
            + "\"nodes\":[\"classify\",\"hint\",\"compose\",\"respond\"],\"llmCalls\":1,\"hintLevel\":2,"
            + "\"suggestHelp\":true,\"searchRound\":1,\"variant\":null,\"retryable\":false,"
            + "\"context\":{\"estimatedInputTokens\":812,\"budget\":3000,"
            + "\"dropped\":{\"messages\":2,\"memories\":0,\"materials\":0,\"screenSummary\":0},\"truncated\":[\"question\"]},"
            + "\"usedMemoryIds\":[\"m_1\"],\"usedSummaryId\":\"s_1\"}}";

    private HttpServer server;
    private final AtomicReference<String> sentBody = new AtomicReference<>();
    private final AtomicReference<String> sentKey = new AtomicReference<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private AiClient serving(int status, String... lines) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/agent/chat", exchange -> {
            sentBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            sentKey.set(exchange.getRequestHeaders().getFirst("X-Internal-Key"));
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
        return new AiClient("http://127.0.0.1:" + server.getAddress().getPort(), "test-key", AiAccessHeaders.none(),
                JSON);
    }

    static class Recorder implements AgentListener {
        final List<String> events = new ArrayList<>();
        final List<String> deltas = new ArrayList<>();
        final List<List<String>> starts = new ArrayList<>();
        final List<AgentDtos.VariantResult> variants = new ArrayList<>();

        @Override
        public void onPing() {
            events.add("ping");
        }

        @Override
        public void onStart(List<String> materialIds) {
            events.add("start");
            starts.add(materialIds);
        }

        @Override
        public void onDelta(String delta) {
            events.add("delta");
            deltas.add(delta);
        }

        @Override
        public void onVariant(AgentDtos.VariantResult result) {
            events.add("variant");
            variants.add(result);
        }
    }

    static AgentDtos.AgentChatRequest personalRequest() {
        return new AgentDtos.AgentChatRequest("personal", "미분이 뭐예요?",
                new AgentDtos.Policy("수학", 3, false, false), List.of("answer_question"),
                new AgentDtos.Context(AgentDtos.Scope.personal(), List.of(), null, List.of(), null),
                List.of(), null, new AgentDtos.Search(0, null));
    }

    static AgentDtos.AgentChatRequest studentRequest() {
        return new AgentDtos.AgentChatRequest("student", "정답 알려줘",
                new AgentDtos.Policy("파이썬", 2, true, true), List.of("answer_question", "material_search"),
                new AgentDtos.Context(AgentDtos.Scope.session(42L),
                        List.of(new AgentDtos.ContextMessage("user", "range가 뭐예요?")), null, List.of(), null),
                List.of(new AgentDtos.Material("m1", "a.pdf", null, "자료")), null, new AgentDtos.Search(0, null));
    }

    @Test
    void readsPingStartDeltasAndMetaInOrder() throws IOException {
        AiClient ai = serving(200, "{\"ping\":true}", "{\"start\":{\"materialIds\":[\"m1\"]}}",
                "{\"delta\":\"a\"}", "", "{\"ping\":true}", "{\"delta\":\"b\"}", META);
        Recorder recorder = new Recorder();

        AgentOutcome outcome = ai.agentStream(studentRequest(), recorder);

        assertThat(recorder.events).containsExactly("ping", "start", "delta", "ping", "delta");
        assertThat(recorder.starts).containsExactly(List.of("m1"));
        assertThat(recorder.deltas).containsExactly("a", "b");
        assertThat(outcome).isInstanceOfSatisfying(AgentOutcome.Completed.class, done -> {
            AgentDtos.Meta meta = done.meta();
            assertThat(meta.route()).isEqualTo("answer_request");
            assertThat(meta.nodes()).containsExactly("classify", "hint", "compose", "respond");
            assertThat(meta.hintLevel()).isEqualTo(2);
            assertThat(meta.suggestHelp()).isTrue();
            assertThat(meta.searchRound()).isEqualTo(1);
            assertThat(meta.variant()).isNull();
            assertThat(meta.retryable()).isFalse();
            assertThat(meta.context().estimatedInputTokens()).isEqualTo(812);
            assertThat(meta.context().budget()).isEqualTo(3000);
            assertThat(meta.context().dropped()).containsEntry("messages", 2);
            assertThat(meta.context().truncated()).containsExactly("question");
            assertThat(meta.usedMemoryIds()).containsExactly("m_1");
            assertThat(meta.usedSummaryId()).isEqualTo("s_1");
        });
    }

    @Test
    void needSearchLineIsReturnedAsOutcome() throws IOException {
        AiClient ai = serving(200, "{\"ping\":true}",
                "{\"status\":\"need_search\",\"query\":\"range 끝값\",\"state\":{\"v\":1,\"route\":\"concept\"}}");
        Recorder recorder = new Recorder();

        AgentOutcome outcome = ai.agentStream(studentRequest(), recorder);

        assertThat(outcome).isInstanceOfSatisfying(AgentOutcome.NeedSearch.class, need -> {
            assertThat(need.query()).isEqualTo("range 끝값");
            assertThat(need.state()).containsEntry("v", 1).containsEntry("route", "concept");
        });
        assertThat(recorder.deltas).isEmpty();
    }

    @Test
    void okVariantLineIsParsedWithAnswerFieldsForInternalUse() throws IOException {
        AiClient ai = serving(200, "{\"variant\":{\"status\":\"ok\",\"attempts\":1,\"variant\":{\"type\":\"CHOICE\","
                + "\"question\":\"Q\",\"choices\":[\"8 km\",\"12 km\"],\"answerIndex\":1,\"explanation\":\"E\"}}}",
                META.replace("\"variant\":null", "\"variant\":\"ok\""));
        Recorder recorder = new Recorder();

        AgentOutcome outcome = ai.agentStream(studentRequest(), recorder);

        assertThat(recorder.variants).hasSize(1);
        AgentDtos.VariantResult result = recorder.variants.getFirst();
        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.variant().type()).isEqualTo("CHOICE");
        assertThat(result.variant().choices()).containsExactly("8 km", "12 km");
        assertThat(result.variant().answerIndex()).isEqualTo(1);
        assertThat(result.variant().explanation()).isEqualTo("E");
        assertThat(((AgentOutcome.Completed) outcome).meta().variant()).isEqualTo("ok");
    }

    @Test
    void failedVariantLineKeepsSnakeCaseFailureReasons() throws IOException {
        AiClient ai = serving(200,
                "{\"variant\":{\"status\":\"failed\",\"attempts\":3,\"failure_reasons\":[\"concept_mismatch\"]}}",
                META.replace("\"variant\":null", "\"variant\":\"failed\""));
        Recorder recorder = new Recorder();

        ai.agentStream(studentRequest(), recorder);

        AgentDtos.VariantResult result = recorder.variants.getFirst();
        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.variant()).isNull();
        assertThat(result.failureReasons()).containsExactly("concept_mismatch");
    }

    @Test
    void errorAfterOnlyPingAndStartIsBeforeOutput() throws IOException {
        AiClient ai = serving(200, "{\"ping\":true}", "{\"start\":{\"materialIds\":[]}}",
                "{\"error\":\"agent_timeout\"}");

        assertThatThrownBy(() -> ai.agentStream(studentRequest(), new Recorder()))
                .isInstanceOfSatisfying(AiUnavailableException.class, e -> assertThat(e.isAfterOutput()).isFalse());
    }

    @Test
    void errorAfterDeltaIsAfterOutput() throws IOException {
        AiClient ai = serving(200, "{\"start\":{\"materialIds\":[]}}", "{\"delta\":\"a\"}",
                "{\"error\":\"llm_unavailable\"}");
        Recorder recorder = new Recorder();

        assertThatThrownBy(() -> ai.agentStream(studentRequest(), recorder))
                .isInstanceOfSatisfying(AiUnavailableException.class, e -> assertThat(e.isAfterOutput()).isTrue());
        assertThat(recorder.deltas).containsExactly("a");
    }

    @Test
    void errorAfterVariantLineIsAfterOutput() throws IOException {
        AiClient ai = serving(200,
                "{\"variant\":{\"status\":\"failed\",\"attempts\":3,\"failure_reasons\":[\"concept_mismatch\"]}}",
                "{\"error\":\"llm_unavailable\"}");

        assertThatThrownBy(() -> ai.agentStream(studentRequest(), new Recorder()))
                .isInstanceOfSatisfying(AiUnavailableException.class, e -> assertThat(e.isAfterOutput()).isTrue());
    }

    @Test
    void toolNotAllowedErrorIsUnavailable() throws IOException {
        AiClient ai = serving(200, "{\"error\":\"tool_not_allowed\"}");

        assertThatThrownBy(() -> ai.agentStream(studentRequest(), new Recorder()))
                .isInstanceOfSatisfying(AiUnavailableException.class, e -> assertThat(e.isAfterOutput()).isFalse());
    }

    @Test
    void streamEndingWithoutMetaOrNeedSearchIsUnavailable() throws IOException {
        AiClient ai = serving(200, "{\"start\":{\"materialIds\":[]}}", "{\"delta\":\"a\"}");

        assertThatThrownBy(() -> ai.agentStream(studentRequest(), new Recorder()))
                .isInstanceOfSatisfying(AiUnavailableException.class, e -> assertThat(e.isAfterOutput()).isTrue());
    }

    @Test
    void brokenLineBeforeOutputIsUnavailableBeforeOutput() throws IOException {
        AiClient ai = serving(200, "{\"ping\":true}", "not json");

        assertThatThrownBy(() -> ai.agentStream(studentRequest(), new Recorder()))
                .isInstanceOfSatisfying(AiUnavailableException.class, e -> assertThat(e.isAfterOutput()).isFalse());
    }

    @Test
    void errorStatusIsUnavailableBeforeOutput() throws IOException {
        AiClient ai = serving(422);

        assertThatThrownBy(() -> ai.agentStream(studentRequest(), new Recorder()))
                .isInstanceOfSatisfying(AiUnavailableException.class, e -> assertThat(e.isAfterOutput()).isFalse());
    }

    @Test
    void connectionRefusedIsUnavailableBeforeOutput() throws IOException {
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        AiClient ai = new AiClient("http://127.0.0.1:" + port, "test-key", AiAccessHeaders.none(), JSON);

        assertThatThrownBy(() -> ai.agentStream(personalRequest(), new Recorder()))
                .isInstanceOfSatisfying(AiUnavailableException.class, e -> assertThat(e.isAfterOutput()).isFalse());
    }

    @Test
    void exceptionFromListenerPropagatesUnchanged() throws IOException {
        AiClient ai = serving(200, "{\"start\":{\"materialIds\":[]}}", "{\"delta\":\"a\"}", META);
        var gone = new IllegalStateException("client gone");
        Recorder throwing = new Recorder() {
            @Override
            public void onDelta(String delta) {
                throw gone;
            }
        };

        assertThatThrownBy(() -> ai.agentStream(studentRequest(), throwing)).isSameAs(gone);
    }

    @Test
    void exceptionFromPingListenerPropagatesSoClientGoneIsSeenWithoutDeltas() throws IOException {
        AiClient ai = serving(200, "{\"ping\":true}", "{\"ping\":true}", META);
        var gone = new IllegalStateException("client gone");
        Recorder throwing = new Recorder() {
            @Override
            public void onPing() {
                throw gone;
            }
        };

        assertThatThrownBy(() -> ai.agentStream(studentRequest(), throwing)).isSameAs(gone);
    }

    @Test
    void sendsCamelCaseContractBodyWithInternalKeyAndNoUserId() throws IOException {
        AiClient ai = serving(200, META);

        ai.agentStream(personalRequest(), new Recorder());

        assertThat(sentKey.get()).isEqualTo("test-key");
        JsonNode body = JSON.readTree(sentBody.get());
        assertThat(body.get("agent").asString()).isEqualTo("personal");
        assertThat(body.get("question").asString()).isEqualTo("미분이 뭐예요?");
        assertThat(body.get("allowedTools").get(0).asString()).isEqualTo("answer_question");
        assertThat(body.get("policy").get("hideAnswers").asBoolean()).isFalse();
        assertThat(body.get("context").get("scope").propertyNames()).containsExactly("kind");
        assertThat(body.get("context").get("scope").get("kind").asString()).isEqualTo("personal");
        assertThat(body.get("search").get("round").asInt()).isZero();
        assertThat(body.get("search").get("state").isNull()).isTrue();
        assertThat(body.has("variantSource")).isTrue();
        assertThat(body.get("variantSource").isNull()).isTrue();
        assertThat(sentBody.get()).doesNotContain("userId");
    }

    @Test
    void sessionScopeCarriesSessionIdAndMaterialPageIsOmittedWhenMissing() throws IOException {
        AiClient ai = serving(200, META);

        ai.agentStream(studentRequest(), new Recorder());

        JsonNode body = JSON.readTree(sentBody.get());
        assertThat(body.get("context").get("scope").get("kind").asString()).isEqualTo("session");
        assertThat(body.get("context").get("scope").get("sessionId").asLong()).isEqualTo(42L);
        assertThat(body.get("materials").get(0).has("page")).isFalse();
        assertThat(body.get("materials").get(0).get("id").asString()).isEqualTo("m1");
    }

    @Test
    void nextRoundStateIsSentBackUnchanged() throws IOException {
        AiClient ai = serving(200, META);
        AgentDtos.AgentChatRequest base = studentRequest();
        AgentDtos.AgentChatRequest round1 = new AgentDtos.AgentChatRequest(base.agent(), base.question(), base.policy(),
                base.allowedTools(), base.context(), base.materials(), null,
                new AgentDtos.Search(1, Map.of("v", 1, "route", "concept")));

        ai.agentStream(round1, new Recorder());

        JsonNode state = JSON.readTree(sentBody.get()).get("search").get("state");
        assertThat(state.get("v").asInt()).isEqualTo(1);
        assertThat(state.get("route").asString()).isEqualTo("concept");
    }
}
