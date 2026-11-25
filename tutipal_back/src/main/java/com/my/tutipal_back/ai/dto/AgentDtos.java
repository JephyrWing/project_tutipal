package com.my.tutipal_back.ai.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * PT-85 POST /v1/agent/chat 요청 · 응답 형식 (docs/AI_WORKFLOW_CONTRACT.md 6 · 7절).
 * 필드 이름은 계약 그대로 camelCase, 예외는 PT-84 결과를 그대로 싣는 failure_reasons 하나.
 * FastAPI 가 extra="forbid" 라 계약에 없는 필드(userId 등)는 넣지 않는다.
 */
public final class AgentDtos {

    private AgentDtos() {
    }

    /** 요청 본문 전체. variantSource 는 null 이어도 키를 남긴다 (계약 7.4 예시). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AgentChatRequest(String agent, String question, Policy policy, List<String> allowedTools,
                                   Context context, List<Material> materials, VariantSource variantSource,
                                   Search search) {
    }

    public record Policy(String topic, int difficulty, boolean hideAnswers, boolean preferMaterials) {
    }

    /** {"kind":"session","sessionId":42} 또는 {"kind":"personal"} (personal 은 sessionId 키 자체가 없음). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Scope(String kind, Long sessionId) {

        public static Scope session(long sessionId) {
            return new Scope("session", sessionId);
        }

        public static Scope personal() {
            return new Scope("personal", null);
        }
    }

    /** summary · screenSummary 는 없으면 null 로 보낸다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Context(Scope scope, List<ContextMessage> messages, Summary summary, List<Memory> memories,
                          String screenSummary) {
    }

    public record ContextMessage(String role, String content) {
    }

    public record Summary(String id, Scope scope, String text) {
    }

    public record Memory(String id, Scope scope, String text) {
    }

    /** 권한 범위에서 찾은 자료 조각. id 는 Spring 이 붙인 불투명 값, page 는 없으면 키를 뺀다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Material(String id, String source, Integer page, String content) {
    }

    /** PT-84 OriginalQuestion 그대로. 학생 답안 필드는 없다 (AC-13). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VariantSource(String type, String question, List<String> choices, Integer answerIndex,
                                String modelAnswer) {
    }

    /** state 는 FastAPI 가 준 값을 해석하지 않고 그대로 돌려준다. 첫 호출은 "state": null. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Search(int round, Map<String, Object> state) {
    }

    /** 변형 문항. 정답 · 해설이 들어 있으므로 SSE 로 보낼 때는 VariantPresenter.visible 을 거친다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VariantQuestion(String type, String question, List<String> choices, Integer answerIndex,
                                  String modelAnswer, String explanation) {
    }

    /** NDJSON {"variant": {...}} 줄. 내부 전용. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VariantResult(String status, int attempts, VariantQuestion variant,
                                @JsonProperty("failure_reasons") List<String> failureReasons) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContextReport(int estimatedInputTokens, int budget, Map<String, Integer> dropped,
                                List<String> truncated) {
    }

    /** NDJSON 마지막 {"meta": {...}} 줄. 로그 한 줄과 SSE agent 이벤트의 재료. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Meta(String agent, String route, List<String> nodes, int llmCalls, Integer hintLevel,
                       boolean suggestHelp, int searchRound, String variant, boolean retryable,
                       ContextReport context, List<String> usedMemoryIds, String usedSummaryId) {
    }

    /** SSE agent 이벤트 (계약 7.7). variant 는 정책 적용본 또는 null. */
    public record AgentEvent(String route, boolean suggestHelp, VariantQuestion variant, boolean answerHidden,
                             boolean retryable) {
    }
}
