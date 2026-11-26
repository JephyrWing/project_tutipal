package com.my.tutipal_back.learning.conversation.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.my.tutipal_back.learning.conversation.repository.ConversationStore;

/**
 * PT-106: every stored read of one Agent recall, in one read-only REPEATABLE READ transaction. All statements see
 * the same snapshot, so the result is all-or-nothing: either the conversation was live and its summary, history and
 * memories are consistent, or it was deleted and its summary and history are empty. (Under READ COMMITTED each
 * statement takes a new snapshot, so a delete committing between two reads could mix an already-read summary with
 * an empty history.) A delete committing after the snapshot is taken is not seen: the request is built from a
 * consistent pre-delete view and the turn's save is refused.
 */
@Service
public class ConversationRecallReader {

    /** live = the bound conversation exists for this user in the snapshot; when false, history and summary are empty. */
    public record Snapshot(boolean live, List<ConversationStore.Message> history, ConversationStore.Summary summary,
                           List<ConversationStore.Memory> memories) {
    }

    private final ConversationStore store;

    public ConversationRecallReader(ConversationStore store) {
        this.store = store;
    }

    /**
     * Liveness first, then (only when live) the bound summary of this conversation and the last maxHistory messages
     * after afterMessageId; the newest maxMemories live memories of the (user, scope) in any case.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot read(long userId, long conversationId, Long summaryId, long afterMessageId, Long sessionId,
                         int maxHistory, int maxMemories) {
        boolean live = store.findOwned(userId, conversationId).isPresent();
        ConversationStore.Summary summary = !live || summaryId == null ? null : store.findSummary(summaryId)
                .filter(s -> s.conversationId() == conversationId)
                .orElse(null);
        List<ConversationStore.Message> history = live
                ? store.recentMessagesAfter(conversationId, afterMessageId, maxHistory) : List.of();
        List<ConversationStore.Memory> memories = store.memories(userId, sessionId, maxMemories);
        return new Snapshot(live, history, summary, memories);
    }
}
