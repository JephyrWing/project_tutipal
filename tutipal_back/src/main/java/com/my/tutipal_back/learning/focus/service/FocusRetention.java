package com.my.tutipal_back.learning.focus.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.ToIntBiFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.my.tutipal_back.learning.focus.FocusProperties;
import com.my.tutipal_back.learning.focus.repository.FocusStore;

/**
 * PT-90 AC-9: hourly deletion of focus samples and states of sessions that ended more than app.focus.retention-days
 * ago, in batches of retention-batch-size. The same job also deletes samples older than app.focus.max-sample-age-days
 * (and states not updated for that long) in every session, because a session the teacher never closed has no
 * ended_at. Logs counts only.
 */
@Component
public class FocusRetention {

    private static final Logger log = LoggerFactory.getLogger(FocusRetention.class);

    private final FocusStore store;
    private final FocusProperties properties;
    private final Clock clock;

    @Autowired
    public FocusRetention(FocusStore store, FocusProperties properties) {
        this(store, properties, Clock.systemUTC());
    }

    public FocusRetention(FocusStore store, FocusProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "PT10M", fixedDelayString = "PT1H")
    void scheduledPurge() {
        try {
            int deleted = purgeExpired() + purgeStale();
            if (deleted > 0) {
                log.info("expired focus rows deleted: count={}", deleted);
            }
        } catch (RuntimeException e) {
            log.warn("focus retention failed: reason={}", e.getClass().getSimpleName());
        }
    }

    /** Returns the number of deleted rows (samples + states). */
    public int purgeExpired() {
        Instant cutoff = clock.instant().minus(Duration.ofDays(properties.retentionDays()));
        return inBatches(store::deleteExpiredSamples, cutoff) + inBatches(store::deleteExpiredStates, cutoff);
    }

    /** Upper bound by age, ended or not. Returns the number of deleted rows (samples + states). */
    public int purgeStale() {
        Instant cutoff = clock.instant().minus(Duration.ofDays(properties.maxSampleAgeDays()));
        return inBatches(store::deleteStaleSamples, cutoff) + inBatches(store::deleteStaleStates, cutoff);
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
