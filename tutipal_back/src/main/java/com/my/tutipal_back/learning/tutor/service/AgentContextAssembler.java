package com.my.tutipal_back.learning.tutor.service;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.my.tutipal_back.ai.dto.AgentDtos;

/**
 * PT-85 계약 6.2 · 6.6: 요청의 context 를 조립한다. 무효 요약 · 삭제 기억 · 범위가 다른 요약/기억은 보내지 않는다
 * (학생 = 같은 세션 것만, 개인 = 개인 학습 것만). 소유자가 요청 사용자가 아닌 요약/기억도 보내지 않는다
 * (포트 구현이 잘못 조회해도 다른 사용자 기억이 섞이지 않게 하는 심층 방어).
 * 캐시 없이 턴마다 포트를 다시 부르므로 삭제가 바로 반영된다.
 */
@Component
public class AgentContextAssembler {

    static final int MAX_MEMORIES = 20;
    private static final int MAX_SUMMARY_LENGTH = 4000;
    private static final int MAX_MEMORY_LENGTH = 1000;
    private static final int MAX_SCREEN_SUMMARY_LENGTH = 4000;
    static final int MAX_MESSAGES = 39;
    private static final int MAX_MESSAGE_LENGTH = 8000;
    private static final Set<String> ROLES = Set.of("user", "assistant");
    private static final Pattern OPAQUE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final ConversationMemory memory;

    public AgentContextAssembler(ConversationMemory memory) {
        this.memory = memory;
    }

    public AgentDtos.Context assemble(Long userId, AgentDtos.Scope scope, String question,
                                      List<AgentDtos.ContextMessage> history, String screenSummary) {
        ConversationMemory.Recall recall = memory.recall(userId, scope, question, history);
        List<AgentDtos.ContextMessage> messages = recall == null ? List.of() : messages(recall.messages());
        ConversationMemory.StoredSummary stored = recall == null ? null : recall.summary();
        AgentDtos.Summary summary = null;
        if (stored != null && stored.invalidatedAt() == null && scope.equals(stored.scope())
                && ownedBy(userId, stored.ownerUserId()) && usable(stored.id(), stored.text())) {
            summary = new AgentDtos.Summary(stored.id(), scope, cut(stored.text(), MAX_SUMMARY_LENGTH));
        }
        List<ConversationMemory.StoredMemory> memories = recall == null || recall.memories() == null
                ? List.of() : recall.memories();
        List<AgentDtos.Memory> kept = memories.stream()
                .filter(m -> m != null && m.deletedAt() == null && scope.equals(m.scope())
                        && ownedBy(userId, m.ownerUserId()) && usable(m.id(), m.text()))
                .limit(MAX_MEMORIES)
                .map(m -> new AgentDtos.Memory(m.id(), scope, cut(m.text(), MAX_MEMORY_LENGTH)))
                .toList();
        String screen = screenSummary == null || screenSummary.isBlank()
                ? null : cut(screenSummary, MAX_SCREEN_SUMMARY_LENGTH);
        return new AgentDtos.Context(scope, messages, summary, kept, screen);
    }

    /**
     * 포트가 준 이력을 계약 상한(최근 40개 · 각 8000자)에 맞춘다. 역할이 user/assistant 가 아니거나 빈 글은 FastAPI 가
     * 422 로 거절하므로 뺀다 (화면 이력은 ChatRequest 검증을 이미 통과해 그대로 지나간다).
     */
    private static List<AgentDtos.ContextMessage> messages(List<AgentDtos.ContextMessage> given) {
        if (given == null || given.isEmpty()) {
            return List.of();
        }
        List<AgentDtos.ContextMessage> valid = given.stream()
                .filter(m -> m != null && ROLES.contains(m.role()) && m.content() != null && !m.content().isBlank())
                .map(m -> m.content().length() > MAX_MESSAGE_LENGTH
                        ? new AgentDtos.ContextMessage(m.role(), cut(m.content(), MAX_MESSAGE_LENGTH)) : m)
                .toList();
        return valid.size() > MAX_MESSAGES ? List.copyOf(valid.subList(valid.size() - MAX_MESSAGES, valid.size()))
                : valid;
    }

    private static boolean ownedBy(Long userId, Long owner) {
        return userId != null && userId.equals(owner);
    }

    /** FastAPI 가 422 로 거절할 값(id 형식 · 빈 글)은 보내지 않는다. */
    private static boolean usable(String id, String text) {
        return id != null && OPAQUE_ID.matcher(id).matches() && text != null && !text.isBlank();
    }

    private static String cut(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }
}
