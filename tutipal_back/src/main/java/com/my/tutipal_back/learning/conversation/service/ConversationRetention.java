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
 * statement that skips rows locked by a turn being saved or a save toggle). Retention follows the PT-13 PM decision
 * (2026-11-26); conversations the owner saved (saved_at) are never removed by retention.
 *
 * <ul>
 *   <li>Deleted purge: conversations and memories the user deleted (deleted_at) more than deleted-purge-days ago
 *       (default 30) are removed; a conversation's messages, summaries and memories go with it through
 *       ON DELETE CASCADE. Saved ones too (deleting is the user's own choice).</li>
 *   <li>A. Class retention: once a session ended more than its ai_retention_days ago (teacher's choice, 0..90), its
 *       unsaved conversations are removed (deleted or not), then its session-scoped memories (memories are per scope,
 *       not per conversation, so a saved conversation's memories follow the session too). Open sessions never.</li>
 *   <li>B. Personal retention: unsaved personal conversations idle (last_message_at) longer than
 *       personal-retention-days (default 30) are removed (deleted or not).</li>
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

    /** Each part runs even if another one fails (logged with the error type only). */
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
            int deleted = purgeExpiredClass();
            if (deleted > 0) {
                log.info("expired class AI conversations / memories deleted: count={}", deleted);
            }
        } catch (RuntimeException e) {
            log.warn("AI conversation class retention failed: reason={}", e.getClass().getSimpleName());
        }
        try {
            int deleted = purgeIdlePersonal();
            if (deleted > 0) {
                log.info("idle personal AI conversations deleted: count={}", deleted);
            }
        } catch (RuntimeException e) {
            log.warn("AI conversation personal retention failed: reason={}", e.getClass().getSimpleName());
        }
    }

    /**
     * A. Removes unsaved conversations of sessions whose ended_at + ai_retention_days is before now, then the
     * remaining session-scoped memories of those sessions. Returns conversations + memories removed (memories that
     * went with a conversation through CASCADE are not counted).
     */
    public int purgeExpiredClass() {
        Instant now = clock.instant();
        return inBatches(store::deleteExpiredClassConversations, now)
                + inBatches(store::deleteExpiredClassMemories, now);
    }

    /** B. Removes unsaved personal conversations whose last activity is older than personal-retention-days. */
    public int purgeIdlePersonal() {
        Instant cutoff = clock.instant().minus(Duration.ofDays(properties.personalRetentionDays()));
        return inBatches(store::deleteIdlePersonalConversationsBefore, cutoff);
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

    private int inBatches(ToIntBiFunction<Instant, Integer> deleteBatch, Instant at) {
        int batch = properties.retentionBatchSize();
        int total = 0;
        int deleted;
        do {
            deleted = deleteBatch.applyAsInt(at, batch);
            total += deleted;
        } while (deleted >= batch);
        return total;
    }
}
