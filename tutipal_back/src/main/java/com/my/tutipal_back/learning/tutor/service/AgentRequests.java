package com.my.tutipal_back.learning.tutor.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

import com.my.tutipal_back.ai.dto.AgentDtos;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.learning.quiz.dto.VariantOriginal;
import com.my.tutipal_back.learning.quiz.entity.QuizType;
import com.my.tutipal_back.learning.tutor.dto.ChatRequest;
import com.my.tutipal_back.rag.dto.RagHit;
import com.my.tutipal_back.sessions.dto.ChatSettings;

import tools.jackson.databind.json.JsonMapper;

/**
 * PT-85 /v1/agent/chat 요청 조립 (계약 7.1). 상태 없는 순수 함수만 둔다.
 * 사용자에게 보이는 고정 문구(도움 요청 추천 · 변형 실패 안내 · 재시도 안내)도 여기 한 곳에.
 */
final class AgentRequests {

    static final int MAX_SEARCH_ROUNDS = 2;
    static final int MAX_STATE_BYTES = 65_536;
    static final int MAX_QUERY_LENGTH = 500;
    static final int MAX_MATERIALS = 3;

    static final String KIND_STUDENT = "student";
    static final String KIND_PERSONAL = "personal";

    static final String TOOL_ANSWER = "answer_question";
    static final String TOOL_SEARCH = "material_search";
    static final String TOOL_VARIANT = "question_variant";

    /** student Agent 가 suggestHelp=true 를 보내면 답변 끝에 붙인다 (도움 요청 자체는 학생이 직접 누른다). */
    static final String HELP_SUGGESTION = "\n\n혼자 풀기 어렵다면 선생님께 도움 요청할래요?";
    /** 변형에 실패했고 FastAPI 일반 답변도 없을 때 (모델 응답 없음 · 시간 부족). */
    static final String VARIANT_FAILED_NOTICE = "지금은 비슷한 문제를 만들지 못했어요. 원래 문제의 풀이 과정을 한 단계씩 다시 살펴볼까요?";
    /** 같은 사용자의 Agent 턴이 이미 진행 중일 때 openChat 이 내는 429 문구. */
    static final String TURN_IN_PROGRESS = "앞 질문의 답을 아직 만들고 있어요. 끝난 뒤 다시 물어봐 주세요";
    /** meta.retryable=true 일 때 덧붙인다. */
    static final String RETRY_NOTICE = "\n\n잠시 후 같은 문항으로 다시 요청하면 새 문제를 만들어 볼게요.";

    private static final int PERSONAL_DIFFICULTY = 3;
    private static final int MAX_SOURCE_LENGTH = 120;
    private static final int MAX_CONTENT_LENGTH = 1500;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 도움 요청 API(POST /api/sessions/{id}/help)의 메시지 상한과 같다. */
    static final int MAX_HELP_MESSAGE = 300;
    private static final int MAX_DRAFT_TOPIC = 30;
    private static final Pattern CONTROL_OR_SPACE = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}\\s]+");
    private static final Map<String, String> ROUTE_LABELS = Map.of(
            "concept", "개념 이해",
            "answer_request", "문제 풀이 요청",
            "answer_check", "내 답 확인",
            "code_debug", "코드 오류");

    private AgentRequests() {
    }

    /** 현재 질문 = 화면이 보낸 messages 의 마지막 원소. user 가 아니면 400 (한도 예약 전에 부른다). */
    static String question(ChatRequest request) {
        ChatRequest.Message last = request.messages().getLast();
        if (!"user".equals(last.role())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "마지막 메시지는 질문이어야 해요");
        }
        return last.content();
    }

    /** 현재 질문 이전의 이력 (마지막 원소를 뺀 나머지, 오래된 것부터). */
    static List<AgentDtos.ContextMessage> history(ChatRequest request) {
        List<ChatRequest.Message> messages = request.messages();
        return messages.subList(0, messages.size() - 1).stream()
                .map(m -> new AgentDtos.ContextMessage(m.role(), m.content()))
                .toList();
    }

    /** 검색 결과 앞 3개를 계약 상한에 맞춰 자른다. id 는 요청 안에서만 쓰는 불투명 값 (m1, m2, m3). */
    static List<AgentDtos.Material> materials(List<RagHit> hits) {
        List<AgentDtos.Material> materials = new ArrayList<>();
        for (RagHit hit : hits) {
            if (materials.size() == MAX_MATERIALS) {
                break;
            }
            if (hit.content() == null || hit.content().isBlank()) {
                continue;
            }
            String source = hit.filename() == null || hit.filename().isBlank() ? "자료" : hit.filename();
            materials.add(new AgentDtos.Material("m" + (materials.size() + 1), cut(source, MAX_SOURCE_LENGTH),
                    hit.page() != null && hit.page() >= 1 ? hit.page() : null, cut(hit.content(), MAX_CONTENT_LENGTH)));
        }
        return List.copyOf(materials);
    }

    /** PT-84 OriginalQuestion. 학생 답안 · 피드백은 싣지 않는다 (AC-13). */
    static AgentDtos.VariantSource variantSource(VariantOriginal original) {
        if (original.type() == QuizType.CHOICE) {
            return new AgentDtos.VariantSource(QuizType.CHOICE.name(), original.question(), original.choices(),
                    original.answerIndex(), null);
        }
        return new AgentDtos.VariantSource(original.type().name(), original.question(), null, null,
                original.modelAnswer());
    }

    /** 수업 학생: 선생님 설정(난이도 · 정답 숨김 · 자료 우선)과 재검색 도구, 변형이면 변형 도구까지. */
    static AgentDtos.AgentChatRequest student(String question, ChatSettings settings, AgentDtos.Context context,
                                              List<AgentDtos.Material> materials, AgentDtos.VariantSource source) {
        List<String> tools = source == null
                ? List.of(TOOL_ANSWER, TOOL_SEARCH)
                : List.of(TOOL_ANSWER, TOOL_SEARCH, TOOL_VARIANT);
        AgentDtos.Policy policy = new AgentDtos.Policy(settings.topic(), Math.clamp(settings.difficulty(), 1, 5),
                settings.hideAnswers(), settings.preferMaterials());
        return new AgentDtos.AgentChatRequest(KIND_STUDENT, question, policy, tools, context, materials, source,
                new AgentDtos.Search(0, null));
    }

    /** 개인 학습: 화면 주제, 정답 공개, 답변 도구만, 자료 · 변형 · 재검색 없음 (계약 7.1). */
    static AgentDtos.AgentChatRequest personal(String question, String topic, AgentDtos.Context context) {
        return new AgentDtos.AgentChatRequest(KIND_PERSONAL, question,
                new AgentDtos.Policy(topic, PERSONAL_DIFFICULTY, false, false), List.of(TOOL_ANSWER), context,
                List.of(), null, new AgentDtos.Search(0, null));
    }

    /** need_search 뒤 다음 호출: round+1, FastAPI state 그대로, 자료는 새 결과로 교체 (D13). */
    static AgentDtos.AgentChatRequest nextRound(AgentDtos.AgentChatRequest previous, Map<String, Object> state,
                                                List<AgentDtos.Material> fresh) {
        return new AgentDtos.AgentChatRequest(previous.agent(), previous.question(), previous.policy(),
                previous.allowedTools(), previous.context(), fresh, previous.variantSource(),
                new AgentDtos.Search(previous.search().round() + 1, state));
    }

    /** state 의 UTF-8 JSON 크기 (계약 6.8 상한 비교용). */
    static int stateBytes(Map<String, Object> state) {
        return JSON.writeValueAsBytes(state).length;
    }

    /**
     * PT-113 도움 요청 메시지 초안. suggestHelp 턴에서만 만든다. 입력이 (주제, 질문 종류, 힌트 단계) 뿐이라
     * 질문 · 답변 원문이나 정답 · 해설이 들어갈 길이 없다. 학생 화면이 메시지 칸에 채우고, 학생이 직접 보낸다.
     */
    static String helpDraft(String topic, String route, Integer hintLevel) {
        List<String> parts = new ArrayList<>();
        String cleanTopic = topic == null ? "" : CONTROL_OR_SPACE.matcher(topic).replaceAll(" ").strip();
        if (!cleanTopic.isEmpty()) {
            parts.add("수업 주제: " + cutCodePoints(cleanTopic, MAX_DRAFT_TOPIC));
        }
        parts.add("질문 종류: " + ROUTE_LABELS.getOrDefault(route == null ? "" : route, "질문"));
        if (hintLevel != null && hintLevel >= 1 && hintLevel <= 3) {
            parts.add("받은 힌트: " + hintLevel + "단계까지");
        }
        parts.add("code_debug".equals(route) ? "코드 오류가 계속 해결되지 않아요." : "힌트를 받고도 혼자 해결하지 못했어요.");
        return cutCodePoints("[AI 조교 요약] " + String.join(" · ", parts), MAX_HELP_MESSAGE);
    }

    private static String cut(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static String cutCodePoints(String text, int max) {
        return text.codePointCount(0, text.length()) > max ? text.substring(0, text.offsetByCodePoints(0, max)) : text;
    }
}
