package com.my.tutipal_back.ai.client;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

import com.my.tutipal_back.ai.dto.AgentDtos;
import com.my.tutipal_back.common.support.TransientConnectionFailure;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * FastAPI(ai 컨테이너) 호출은 전부 여기 한 곳으로. 내부 키를 붙이고, 스트림을 줄 단위로 읽는다.
 * tutor · quiz · material · screensummary · review 가 같이 쓴다.
 */
@Component
public class AiClient {

    /** LLM 에 넘기는 대화 한 줄 (role = user | assistant). images = base64 이미지 한 장 (화면 요약용, 없으면 null). */
    public record Message(String role, String content, @JsonInclude(JsonInclude.Include.NON_NULL) List<String> images) {
        public Message(String role, String content) {
            this(role, content, null);
        }
    }

    private record Chunk(String delta, String error) {
    }

    private final RestClient rest;
    private final JsonMapper json;

    public AiClient(@Value("${app.ai.base-url}") String baseUrl,
                    @Value("${app.ai.internal-key}") String internalKey,
                    JsonMapper json) {
        // HTTP/1.1 고정: JDK 기본(HTTP/2 업그레이드 시도)을 uvicorn 이 거부해서 요청이 깨짐
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        // Spring 의 이 제한은 조각 사이가 아니라 응답 전체에 걸린다 → 답변 하나의 최대 길이로 넉넉히.
        // 조각 사이 멈춤(180초)은 FastAPI 쪽 httpx 가 끊고 error 줄을 보낸다.
        factory.setReadTimeout(Duration.ofMinutes(10));
        this.rest = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(baseUrl)
                .defaultHeader("X-Internal-Key", internalKey)
                .build();
        this.json = json;
    }

    /**
     * 답변을 글자 조각 단위로 onDelta 에 넘긴다.
     * AI 서버 연결 실패 · 타임아웃 · 오류 응답 · 깨진 줄은 전부 AiUnavailableException.
     * onDelta 가 던진 예외(예: 화면이 연결을 끊음)는 그대로 올라간다.
     */
    public void chatStream(String system, List<Message> messages, Consumer<String> onDelta) {
        exchangeChat(system, messages, false, onDelta);
    }

    /** 답변 전체를 한 번에 (채점 · 문제 만들기). jsonMode 면 모델이 JSON 하나만 낸다. */
    public String complete(String system, List<Message> messages, boolean jsonMode) {
        StringBuilder sb = new StringBuilder();
        exchangeChat(system, messages, jsonMode, sb::append);
        return sb.toString();
    }

    /** pgvector 리터럴 "[0.1,0.2,…]" (JDBC 에서 ?::vector 로 씀). 자료 RAG · 복습이 같이 씀 */
    public static String pgVector(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            sb.append(i == 0 ? "" : ",").append(v[i]);
        }
        return sb.append(']').toString();
    }

    /** 자료 파일에서 글자 뽑기 (txt · md · pdf). page 는 pdf 쪽 번호, 아니면 null */
    public record Page(Integer page, String text) {
    }

    public record EmbedResponse(String model, List<float[]> vectors) {
    }

    /** 임베딩 (Ollama bge-m3, 1024차원). 한 번에 64개까지 */
    public List<float[]> embed(List<String> texts) {
        return embedWithModel(texts).vectors();
    }

    public EmbedResponse embedWithModel(List<String> texts) {
        try {
            EmbedResponse res = rest.post().uri("/v1/embed")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("texts", texts))
                    .retrieve()
                    .body(EmbedResponse.class);
            if (res == null || res.model() == null || res.model().isBlank() || res.model().length()>200
                    || res.vectors() == null || res.vectors().size() != texts.size()) {
                throw new AiUnavailableException();
            }
            for(float[] vector:res.vectors()) {
                if(vector==null || vector.length!=1024) throw new AiUnavailableException();
                for(float v:vector)if(!Float.isFinite(v))throw new AiUnavailableException();
            }
            return res;
        } catch (ResourceAccessException e) {
            throw new AiUnavailableException(TransientConnectionFailure.confirmed(e));
        } catch (RestClientResponseException e) {
            var headers = e.getResponseHeaders();
            // FastAPI가 확인된 장애에 붙인 내부 표지만 신뢰한다.
            throw new AiUnavailableException(e.getStatusCode().value() == 503 && headers != null
                    && "transient".equals(headers.getFirst("X-Embedding-Failure")));
        } catch (RestClientException e) {
            throw new AiUnavailableException();
        }
    }

    /**
     * AI 서버 쪽 실패는 전부 AiUnavailableException 으로 바꾼다. 첫 답변 조각을 받은 뒤의 실패면
     * {@link AiUnavailableException#afterOutput()} (모델이 이미 일함 → PT-97 한도에서 환불하지 않음).
     */
    private void exchangeChat(String system, List<Message> messages, boolean jsonMode, Consumer<String> onDelta) {
        boolean[] emitted = {false};
        try {
            rest.post().uri("/v1/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("system", system, "messages", messages, "json_mode", jsonMode))
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new AiUnavailableException();
                        }
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.isBlank()) {
                                    continue;
                                }
                                Chunk chunk = json.readValue(line, Chunk.class);
                                if (chunk.error() != null) {
                                    throw unavailable(emitted[0]);
                                }
                                if (chunk.delta() != null) {
                                    emitted[0] = true;
                                    onDelta.accept(chunk.delta());
                                }
                            }
                        }
                        return null;
                    });
        } catch (RestClientException | JacksonException e) {
            throw unavailable(emitted[0]);
        }
    }

    /**
     * PT-85 Agent 한 번 호출 (POST /v1/agent/chat, 계약 7.5). 줄마다 listener 를 부르고, need_search 줄이나
     * 마지막 meta 줄로 끝난 방식을 돌려준다.
     * AI 서버 연결 실패 · 오류 응답 · error 줄 · 깨진 줄 · meta 없이 끝난 스트림은 AiUnavailableException.
     * delta 또는 variant 줄을 받은 뒤의 실패면 afterOutput (계약 7.6: 모델이 이미 일함 → 환불 안 함).
     * ping · start · need_search 는 출력으로 세지 않는다. listener 가 던진 예외는 그대로 올라간다.
     */
    public AgentOutcome agentStream(AgentDtos.AgentChatRequest body, AgentListener listener) {
        boolean[] emitted = {false};
        try {
            AgentOutcome outcome = rest.post().uri("/v1/agent/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new AiUnavailableException();
                        }
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.isBlank()) {
                                    continue;
                                }
                                AgentOutcome done = agentLine(json.readTree(line), listener, emitted);
                                if (done != null) {
                                    return done;
                                }
                            }
                        }
                        return null;
                    });
            if (outcome == null) {
                throw unavailable(emitted[0]);   // meta · need_search 없이 끊김
            }
            return outcome;
        } catch (RestClientException | JacksonException e) {
            throw unavailable(emitted[0]);
        }
    }

    /** 줄 하나 처리. 끝을 알리는 줄이면 결과, 아니면 null. 모르는 종류의 줄은 건너뛴다. */
    @SuppressWarnings("unchecked")
    private AgentOutcome agentLine(JsonNode line, AgentListener listener, boolean[] emitted) {
        if (!line.isObject()) {
            throw unavailable(emitted[0]);
        }
        if (line.has("error")) {
            throw unavailable(emitted[0]);
        }
        if (line.has("ping")) {
            listener.onPing();
        } else if (line.has("start")) {
            JsonNode ids = line.get("start").path("materialIds");
            List<String> materialIds = new ArrayList<>();
            for (JsonNode id : ids) {
                materialIds.add(id.asString());
            }
            listener.onStart(List.copyOf(materialIds));
        } else if (line.has("delta")) {
            JsonNode delta = line.get("delta");
            if (!delta.isString()) {
                throw unavailable(emitted[0]);
            }
            emitted[0] = true;
            listener.onDelta(delta.asString());
        } else if (line.has("variant")) {
            AgentDtos.VariantResult result = json.treeToValue(line.get("variant"), AgentDtos.VariantResult.class);
            emitted[0] = true;   // 변형 도구가 모델을 이미 썼다
            listener.onVariant(result);
        } else if ("need_search".equals(line.path("status").asString(""))) {
            JsonNode state = line.get("state");
            Map<String, Object> parsed = state != null && state.isObject()
                    ? json.treeToValue(state, Map.class) : null;
            return new AgentOutcome.NeedSearch(line.path("query").asString(""), parsed);
        } else if (line.has("meta")) {
            return new AgentOutcome.Completed(json.treeToValue(line.get("meta"), AgentDtos.Meta.class));
        }
        return null;
    }

    private static AiUnavailableException unavailable(boolean afterOutput) {
        return afterOutput ? AiUnavailableException.afterOutput() : new AiUnavailableException();
    }
}
