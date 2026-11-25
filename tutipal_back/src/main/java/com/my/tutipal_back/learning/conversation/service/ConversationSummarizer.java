package com.my.tutipal_back.learning.conversation.service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.ai.client.AiSlots;
import com.my.tutipal_back.ai.dto.AgentDtos;
import com.my.tutipal_back.learning.conversation.ConversationProperties;
import com.my.tutipal_back.learning.conversation.repository.ConversationStore;

/**
 * PT-106 AC-21: refreshes a conversation's summary outside the chat turn (contract 6.5), after the turn's response
 * completed and its turn guard was released.
 *
 * <ul>
 *   <li>At most one run per conversation at a time (a second call returns at once); other conversations run
 *       concurrently.</li>
 *   <li>At most summary.max-concurrent runs server-wide (production: 1, so summaries never take every AiSlots seat
 *       from chat); without a free summary seat the run is skipped at once.</li>
 *   <li>Waits only a short configured time for an AiSlots seat, otherwise skips (the next turn retries).</li>
 *   <li>No CHAT quota: the user did not ask for it. Skipped while the Agent path is disabled.</li>
 *   <li>Failure keeps the previous summary. Logs only the conversation id and the error type, never text.</li>
 *   <li>The result is discarded if another summary became active or the conversation was deleted meanwhile.</li>
 * </ul>
 */
@Component
public class ConversationSummarizer {

    private static final Logger log = LoggerFactory.getLogger(ConversationSummarizer.class);
    /** Contract 6.5: summary text is at most 4000 characters (V21 CHECK). */
    static final int MAX_SUMMARY_LENGTH = 4000;

    private final ConversationStore store;
    private final ConversationService conversations;
    private final AiClient ai;
    private final AiSlots slots;
    private final SummaryPolicy policy;
    private final ConversationProperties properties;
    private final boolean agentEnabled;
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    /** Server-wide summary seats (separate from AiSlots: a summary holds one of each). */
    private final Semaphore seats;

    public ConversationSummarizer(ConversationStore store, ConversationService conversations, AiClient ai,
                                  AiSlots slots, SummaryPolicy policy, ConversationProperties properties,
                                  @Value("${app.ai.agent.enabled:false}") boolean agentEnabled) {
        this.store = store;
        this.conversations = conversations;
        this.ai = ai;
        this.slots = slots;
        this.policy = policy;
        this.properties = properties;
        this.agentEnabled = agentEnabled;
        this.seats = new Semaphore(properties.summary().maxConcurrent());
    }

    /** Never throws. */
    public void summarizeIfNeeded(long conversationId) {
        if (!agentEnabled || !running.add(conversationId)) {
            return;
        }
        try {
            summarize(conversationId);
        } catch (RuntimeException e) {
            log.warn("conversation summary skipped: conversation={} reason={}", conversationId,
                    e.getClass().getSimpleName());
        } finally {
            running.remove(conversationId);
        }
    }

    private void summarize(long conversationId) {
        Optional<AgentDtos.Scope> scope = conversations.scopeOf(conversationId);
        if (scope.isEmpty()) {
            return;
        }
        ConversationStore.Summary active = store.activeSummary(conversationId).orElse(null);
        long after = active == null ? 0L : active.toMessageId();
        int count = store.countMessagesAfter(conversationId, after);
        if (count - properties.summary().keepRecent() < properties.summary().threshold()) {
            return;
        }
        List<ConversationStore.Message> oldest = store.oldestMessagesAfter(conversationId, after, policy.maxInput());
        Optional<SummaryPlan> plan = policy.plan(active, count, oldest);
        if (plan.isEmpty()) {
            return;
        }
        SummaryPlan p = plan.get();
        if (!seats.tryAcquire()) {
            log.debug("conversation summary skipped, no free summary seat: conversation={}", conversationId);
            return;
        }
        String text;
        try {
            text = slots.run(properties.summary().slotWait(),
                    () -> ai.summarize(scope.get(), p.messages(), p.previousSummary()));
        } finally {
            seats.release();
        }
        String stored = ConversationService.cut(text.strip(), MAX_SUMMARY_LENGTH);
        if (stored.isEmpty()) {
            return;
        }
        if (!conversations.saveSummary(conversationId, p, stored)) {
            log.info("conversation summary discarded: conversation={}", conversationId);
        }
    }
}
