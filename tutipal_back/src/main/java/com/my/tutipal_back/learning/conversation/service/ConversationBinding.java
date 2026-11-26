package com.my.tutipal_back.learning.conversation.service;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.my.tutipal_back.ai.dto.AgentDtos;

/**
 * PT-106: which stored conversation the current turn belongs to, for {@link StoredConversationMemory}.
 *
 * <p>TutorService (owned by PT-113 right now) must not change, so the conversation turn wraps tutor.chat in
 * {@link #run} and the memory port reads the binding on the same thread. This relies on TutorService assembling the
 * Agent context synchronously on the calling thread (guarded by ConversationTurnAgentIntegrationTest). Plain
 * ThreadLocal on purpose: threads started inside the turn must not inherit it. Follow-up after PT-113 merges:
 * carry the conversation id in ChatRequest and drop this class.
 */
@Component
public class ConversationBinding {

    /**
     * userId / scope: the turn's owner and Agent scope (recall with anything else uses nothing stored).
     * summaryId: active summary when the turn started (re-queried at recall). afterMessageId: the history window
     * start (that summary's to_message_id, 0 without one); recall reads the messages after it from the store.
     */
    public record Bound(long userId, AgentDtos.Scope scope, long conversationId, Long summaryId,
                        long afterMessageId) {
    }

    private final ThreadLocal<Bound> current = new ThreadLocal<>();

    /** Runs work with the binding visible on this thread; always removes it afterwards (normal, exception, Error). */
    public void run(Bound bound, Runnable work) {
        Bound previous = current.get();
        current.set(bound);
        try {
            work.run();
        } finally {
            if (previous == null) {
                current.remove();
            } else {
                current.set(previous);
            }
        }
    }

    public Optional<Bound> current() {
        return Optional.ofNullable(current.get());
    }
}
