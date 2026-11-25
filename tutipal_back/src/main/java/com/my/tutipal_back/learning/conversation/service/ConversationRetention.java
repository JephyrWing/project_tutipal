package com.my.tutipal_back.learning.conversation.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.my.tutipal_back.learning.conversation.ConversationProperties;
import com.my.tutipal_back.learning.conversation.repository.ConversationStore;

/**
 * PT-106 AC-24: optional retention for stored AI conversations. PT-13 has no retention decision, so the default
 * (retention-days 0) does nothing and conversations stay until the user deletes them or the account is deleted.
 * With N days, conversations whose last message is older than N days are deleted in batches (each batch is one
 * statement; messages, summaries and memories go with ON DELETE CASCADE).
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
        int batch = properties.retentionBatchSize();
        int total = 0;
        int deleted;
        do {
            deleted = store.deleteInactiveBefore(cutoff, batch);
            total += deleted;
        } while (deleted >= batch);
        return total;
    }
}
