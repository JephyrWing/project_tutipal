package com.my.tutipal_back.learning.conversation.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.ToIntBiFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.my.tutipal_back.learning.conversation.ConversationProperties;
import com.my.tutipal_back.learning.conversation.repository.ConversationStore;

/**
 * PT-106 physical deletion of stored AI conversations, hourly, in batches of retention-batch-size (each batch is one
 * statement that skips rows locked by a turn being saved).
 *
 * <ul>
 *   <li>Deleted purge: conversations and memories the user deleted (deleted_at) more than deleted-purge-days ago
 *       (default 30, tentative until PT-13) are removed; a conversation's messages, summaries and memories go with it
 *       through ON DELETE CASCADE.</li>
 *   <li>AC-24 inactivity retention: PT-13 has no retention decision, so the default (retention-days 0) does nothing.
 *       With N days, conversations whose last message is older than N days are removed, deleted or not.</li>
 * </ul>
 */
@Component
public class ConversationRetention {

    private static final Logger log = LoggerFactory.getLogger(ConversationRetention.class);

    private final ConversationStore store;
    private final ConversationProperties properties;
    private final Clock clock;

    @Autowired
    public ConversationRetention(ConversationStore store, ConversationProperties properties) {
        this(store, properties, Clock.systemUTC());
    }

    public ConversationRetention(ConversationStore store, ConversationProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "PT10M", fixedDelayString = "PT1H")
    void scheduledPurge() {
        try {
            int purged = purgeDeleted();
            if (purged > 0) {
                log.info("deleted AI conversations / memories purged: count={}", purged);
            }
        } catch (RuntimeException e) {
            log.warn("AI conversation deleted purge failed: reason={}", e.getClass().getSimpleName());
        }
        try {
            int deleted = purgeExpired();
            if (deleted > 0) {
                log.info("expired AI conversations deleted: count={}", deleted);
            }
        } catch (RuntimeException e) {
            log.warn("AI conversation retention failed: reason={}", e.getClass().getSimpleName());
        }
    }

    /** Returns the number of deleted conversations (0 without touching the store when retention is off). */
    public int purgeExpired() {
        if (properties.retentionDays() <= 0) {
            return 0;
        }
        Instant cutoff = clock.instant().minus(Duration.ofDays(properties.retentionDays()));
        return inBatches(store::deleteInactiveBefore, cutoff);
    }

    /**
     * Physically removes conversations, then memories, whose deleted_at is older than deleted-purge-days (always
     * at least 1, checked at startup). Returns the number of removed rows (conversations + memories; memories that
     * went with a conversation through CASCADE are not counted).
     */
    public int purgeDeleted() {
        Instant cutoff = clock.instant().minus(Duration.ofDays(properties.deletedPurgeDays()));
        return inBatches(store::purgeDeletedConversationsBefore, cutoff)
                + inBatches(store::purgeDeletedMemoriesBefore, cutoff);
    }

    private int inBatches(ToIntBiFunction<Instant, Integer> deleteBatch, Instant cutoff) {
        int batch = properties.retentionBatchSize();
        int total = 0;
        int deleted;
        do {
            deleted = deleteBatch.applyAsInt(cutoff, batch);
            total += deleted;
        } while (deleted >= batch);
        return total;
    }
}
