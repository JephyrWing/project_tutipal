package com.my.tutipal_back.learning.conversation.service;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.my.tutipal_back.ai.dto.AgentDtos;
import com.my.tutipal_back.learning.conversation.repository.ConversationStore;
import com.my.tutipal_back.learning.tutor.service.ConversationMemory;

/**
 * PT-106 implementation of the PT-85 ConversationMemory port (contract 6.7). Being a {@code @Component}, it replaces
 * NoStoredConversationMemory (registered only when no other bean exists).
 *
 * <p>Bound turn (POST /api/conversations/{id}/messages): the stored history read at recall time (the last 39
 * messages after the bound summary's range, each cut to 8000, in one live-filtered query), the summary bound at
 * turn start
 * (re-queried every time, so an invalidated or deleted one is dropped; the assembler filters invalidated ones), and
 * the newest 20 memories of the same (user, scope). Unbound turn (/api/tutor/chat) or a user / scope mismatch:
 * the screen history and nothing stored, without touching the store. No cache: every recall re-queries.
 *
 * <p>Fail-closed: if the bound conversation was deleted after the turn opened, its history and summary are not
 * used (empty history, no summary); live memories of the same scope from other conversations still are. Every
 * recall reads everything through {@link ConversationRecallReader} in one REPEATABLE READ snapshot, so the
 * request is either a consistent live view or (deleted) has no history and no summary of it. The legacy path
 * (agent disabled) uses the ChatRequest.messages snapshot taken when the turn opened and is not affected by this.
 */
@Component
public class StoredConversationMemory implements ConversationMemory {

    static final int MAX_MEMORIES = 20;
    static final String SUMMARY_PREFIX = "s_";
    static final String MEMORY_PREFIX = "m_";

    /** The only way this port reads stored data: one consistent snapshot per recall. */
    private final ConversationRecallReader reader;
    private final ConversationBinding binding;

    @Autowired
    public StoredConversationMemory(ConversationRecallReader reader, ConversationBinding binding) {
        this.reader = reader;
        this.binding = binding;
    }

    /** Tests: a reader over the given store (no transaction proxy, so no snapshot isolation). */
    public StoredConversationMemory(ConversationStore store, ConversationBinding binding) {
        this(new ConversationRecallReader(store), binding);
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
        ConversationRecallReader.Snapshot read = reader.read(b.userId(), b.conversationId(), b.summaryId(),
                b.afterMessageId(), scope.sessionId(), ConversationService.MAX_HISTORY, MAX_MEMORIES);
        ConversationStore.Summary s = read.summary();
        StoredSummary summary = s == null ? null
                : new StoredSummary(SUMMARY_PREFIX + s.id(), b.userId(), scope, s.text(), s.invalidatedAt());
        List<AgentDtos.ContextMessage> history = read.history().stream()
                .map(m -> new AgentDtos.ContextMessage(m.role(),
                        ConversationService.cut(m.content(), ConversationService.MAX_HISTORY_MESSAGE_LENGTH)))
                .toList();
        List<StoredMemory> memories = read.memories().stream()
                .map(m -> new StoredMemory(MEMORY_PREFIX + m.id(), m.userId(), scopeOf(m), m.text(), null))
                .toList();
        return new Recall(history, summary, memories);
    }

    private static AgentDtos.Scope scopeOf(ConversationStore.Memory memory) {
        return memory.sessionId() == null ? AgentDtos.Scope.personal() : AgentDtos.Scope.session(memory.sessionId());
    }
}
