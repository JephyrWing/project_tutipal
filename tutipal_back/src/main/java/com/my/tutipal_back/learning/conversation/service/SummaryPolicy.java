package com.my.tutipal_back.learning.conversation.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.my.tutipal_back.ai.dto.AgentDtos;
import com.my.tutipal_back.learning.conversation.ConversationProperties;
import com.my.tutipal_back.learning.conversation.repository.ConversationStore;

/**
 * PT-106 AC-20 (pure): when to summarize and what goes in. Messages after the active summary, minus the newest
 * keepRecent, must reach threshold; then the oldest of them (at most maxInput, each cut to 8000) are summarized
 * together with the previous summary, so at least keepRecent messages always stay raw. The input also stops once
 * the cut texts would exceed maxInputChars in total (at least one message always goes in); the rest waits for the
 * next run. FastAPI fits what it receives to the local model's context again.
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
        List<AgentDtos.ContextMessage> messages = new ArrayList<>(take);
        int chars = 0;
        long lastId = 0;
        for (ConversationStore.Message m : oldestAfter.subList(0, take)) {
            String content = ConversationService.cut(m.content(), MAX_MESSAGE_LENGTH);
            if (!messages.isEmpty() && chars + content.length() > rules.maxInputChars()) {
                break;
            }
            messages.add(new AgentDtos.ContextMessage(m.role(), content));
            chars += content.length();
            lastId = m.id();
        }
        return Optional.of(new SummaryPlan(active == null ? null : active.id(), active == null ? null : active.text(),
                messages, oldestAfter.getFirst().id(), lastId));
    }
}
