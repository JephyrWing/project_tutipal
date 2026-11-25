package com.my.tutipal_back.learning.conversation.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.my.tutipal_back.ai.dto.AgentDtos;
import com.my.tutipal_back.learning.conversation.ConversationProperties;
import com.my.tutipal_back.learning.conversation.repository.ConversationStore;

/**
 * PT-106 AC-20 (pure): when to summarize and what goes in. Messages after the active summary, minus the newest
 * keepRecent, must reach threshold; then the oldest of them (at most maxInput, each cut to 8000) are summarized
 * together with the previous summary, so at least keepRecent messages always stay raw.
 */
@Component
public class SummaryPolicy {

    /** Contract 6.5: each summarize input message is at most 8000 characters. */
    static final int MAX_MESSAGE_LENGTH = 8000;

    private final ConversationProperties.Summary rules;

    public SummaryPolicy(ConversationProperties properties) {
        this.rules = properties.summary();
    }

    /** Number of messages to fetch with oldestMessagesAfter. */
    public int maxInput() {
        return rules.maxInput();
    }

    /**
     * @param active      active summary or null
     * @param countAfter  number of messages after active.toMessageId (all messages when null)
     * @param oldestAfter the oldest messages after it, oldest first (at least maxInput when that many exist)
     */
    public Optional<SummaryPlan> plan(ConversationStore.Summary active, int countAfter,
                                      List<ConversationStore.Message> oldestAfter) {
        int eligible = countAfter - rules.keepRecent();
        if (eligible < rules.threshold() || oldestAfter == null || oldestAfter.isEmpty()) {
            return Optional.empty();
        }
        int take = Math.min(Math.min(eligible, rules.maxInput()), oldestAfter.size());
        List<ConversationStore.Message> input = oldestAfter.subList(0, take);
        List<AgentDtos.ContextMessage> messages = input.stream()
                .map(m -> new AgentDtos.ContextMessage(m.role(), cut(m.content())))
                .toList();
        return Optional.of(new SummaryPlan(active == null ? null : active.id(), active == null ? null : active.text(),
                messages, input.getFirst().id(), input.getLast().id()));
    }

    private static String cut(String text) {
        return text.length() > MAX_MESSAGE_LENGTH ? text.substring(0, MAX_MESSAGE_LENGTH) : text;
    }
}
