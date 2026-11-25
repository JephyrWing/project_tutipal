package com.my.tutipal_back.learning.tutor.service;

import java.util.List;

import com.my.tutipal_back.ai.dto.AgentDtos;

/**
 * PT-85 기본 구현: 서버에 저장한 이력 · 요약 · 기억이 아직 없다 (화면이 보낸 이력을 그대로 씀).
 * 빈으로는 {@link ConversationMemoryConfig} 가 다른 구현이 없을 때만 등록한다 (PT-106 구현을 추가하면 자동으로 물러남).
 */
public class NoStoredConversationMemory implements ConversationMemory {

    @Override
    public Recall recall(Long userId, AgentDtos.Scope scope, String question,
                         List<AgentDtos.ContextMessage> screenHistory) {
        return new Recall(screenHistory == null ? List.of() : screenHistory, null, List.of());
    }
}
