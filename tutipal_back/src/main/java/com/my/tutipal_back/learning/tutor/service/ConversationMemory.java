package com.my.tutipal_back.learning.tutor.service;

import java.time.Instant;
import java.util.List;

import com.my.tutipal_back.ai.dto.AgentDtos;

/**
 * PT-85 계약 6.7: Agent 컨텍스트에 넣을 대화 이력 · 요약 · 기억을 꺼내는 포트. PT-106 은 이 포트 구현만 바꾸고
 * TutorService 는 고치지 않는다. 무효 요약 · 삭제 기억 · 범위나 소유자가 다른 요약/기억이 섞여 와도
 * {@link AgentContextAssembler} 가 걸러 낸다.
 *
 * <p>쓰기(메시지 · 요약 저장, 기억 생성 · 삭제, 요약 무효화)는 이 포트에 없다. 쓰기 지점과 시점은 PT-106 이 정한다
 * (계약 6.7: 턴 안에서 요약 · 기억을 만들지 않는다).
 */
@FunctionalInterface
public interface ConversationMemory {

    /**
     * 사용자 · 범위 · 현재 질문 기준으로 이력, 요약 0..1개, 관련도 순 기억을 돌려준다. 턴마다 새로 부른다.
     *
     * @param screenHistory 화면이 보낸 현재 질문 이전 이력 (오래된 것부터). 서버 저장분이 없는 구현은 그대로 돌려준다.
     */
    Recall recall(Long userId, AgentDtos.Scope scope, String question, List<AgentDtos.ContextMessage> screenHistory);

    /** ownerUserId: 이 요약을 가진 사용자. invalidatedAt 이 있으면 원본 메시지가 삭제된 무효 요약. */
    record StoredSummary(String id, Long ownerUserId, AgentDtos.Scope scope, String text, Instant invalidatedAt) {
    }

    /** ownerUserId: 이 기억을 가진 사용자. deletedAt 이 있으면 삭제된 기억. */
    record StoredMemory(String id, Long ownerUserId, AgentDtos.Scope scope, String text, Instant deletedAt) {
    }

    /** messages: 현재 질문 이전 이력 (오래된 것부터). null 이면 이력 없음. */
    record Recall(List<AgentDtos.ContextMessage> messages, StoredSummary summary, List<StoredMemory> memories) {
    }
}
