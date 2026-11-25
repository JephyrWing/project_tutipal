package com.my.tutipal_back.learning.conversation.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.my.tutipal_back.ai.dto.AgentDtos;
import com.my.tutipal_back.learning.conversation.repository.ConversationStore;
import com.my.tutipal_back.learning.tutor.service.ConversationMemory;

/**
 * PT-106 implementation of the PT-85 ConversationMemory port (contract 6.7). Being a {@code @Component}, it replaces
 * NoStoredConversationMemory (registered only when no other bean exists).
 *
 * <p>Bound turn (POST /api/conversations/{id}/messages): server-built history, the summary bound at turn start
 * (re-queried every time, so an invalidated or deleted one is dropped; the assembler filters invalidated ones), and
 * the newest 20 memories of the same (user, scope). Unbound turn (/api/tutor/chat) or a user / scope mismatch:
 * the screen history and nothing stored, without touching the store. No cache: every recall re-queries.
 */
@Component
public class StoredConversationMemory implements ConversationMemory {

    static final int MAX_MEMORIES = 20;
    static final String SUMMARY_PREFIX = "s_";
    static final String MEMORY_PREFIX = "m_";

    private final ConversationStore store;
    private final ConversationBinding binding;

    public StoredConversationMemory(ConversationStore store, ConversationBinding binding) {
        this.store = store;
        this.binding = binding;
    }

    @Override
    public Recall recall(Long userId, AgentDtos.Scope scope, String question,
                         List<AgentDtos.ContextMessage> screenHistory) {
        Optional<ConversationBinding.Bound> bound = binding.current()
                .filter(b -> userId != null && b.userId() == userId && b.scope().equals(scope));
        if (bound.isEmpty()) {
            return new Recall(screenHistory == null ? List.of() : screenHistory, null, List.of());
        }
        ConversationBinding.Bound b = bound.get();
        StoredSummary summary = b.summaryId() == null ? null : store.findSummary(b.summaryId())
                .filter(s -> s.conversationId() == b.conversationId())
                .map(s -> new StoredSummary(SUMMARY_PREFIX + s.id(), b.userId(), scope, s.text(), s.invalidatedAt()))
                .orElse(null);
        List<StoredMemory> memories = store.memories(b.userId(), scope.sessionId(), MAX_MEMORIES).stream()
                .map(m -> new StoredMemory(MEMORY_PREFIX + m.id(), m.userId(), scopeOf(m), m.text(), null))
                .toList();
        return new Recall(b.history(), summary, memories);
    }

    private static AgentDtos.Scope scopeOf(ConversationStore.Memory memory) {
        return memory.sessionId() == null ? AgentDtos.Scope.personal() : AgentDtos.Scope.session(memory.sessionId());
    }
}
