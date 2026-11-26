package com.my.tutipal_back.learning.conversation.service;

import java.util.List;

import com.my.tutipal_back.ai.dto.AgentDtos;

/**
 * PT-106: one summary run. basedOnSummaryId: the active summary the plan extends (null for the first one); the
 * result is discarded if another summary became active meanwhile. fromMessageId..toMessageId: the input range.
 */
public record SummaryPlan(Long basedOnSummaryId, String previousSummary, List<AgentDtos.ContextMessage> messages,
                          long fromMessageId, long toMessageId) {

    public SummaryPlan {
        messages = List.copyOf(messages);
    }
}
