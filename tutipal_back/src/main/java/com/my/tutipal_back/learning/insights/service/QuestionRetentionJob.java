package com.my.tutipal_back.learning.insights.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.my.tutipal_back.learning.insights.repository.ChatQuestionStore;

/**
 * PT-87 AC-7 / D6: deletes stored student questions older than app.insights.questions.retention (temporary 30 days
 * until PT-13 decides the retention), 1000 rows per statement until a short batch. Both keys live only in
 * application.yaml (no code default, same as the AI quota).
 */
@Component
public class QuestionRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(QuestionRetentionJob.class);
    static final int BATCH = 1000;

    private final ChatQuestionStore store;
    private final Duration retention;
    private final Clock clock;

    @Autowired
    public QuestionRetentionJob(ChatQuestionStore store,
                                @Value("${app.insights.questions.retention}") Duration retention) {
        this(store, retention, Clock.systemUTC());
    }

    QuestionRetentionJob(ChatQuestionStore store, Duration retention, Clock clock) {
        if (retention == null || retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("app.insights.questions.retention 은 0보다 커야 해요");
        }
        this.store = store;
        this.retention = retention;
        this.clock = clock;
    }

    /** Each batch is its own statement (autocommit), so a long purge never holds one big transaction. */
    @Scheduled(fixedDelayString = "${app.insights.questions.cleanup-interval}")
    public void purgeExpired() {
        Instant cutoff = clock.instant().minus(retention);
        int total = 0;
        int deleted;
        do {
            deleted = store.deleteCreatedBefore(cutoff, BATCH);
            total += deleted;
        } while (deleted >= BATCH);
        if (total > 0) {
            log.info("expired student questions deleted: {}", total);
        }
    }
}
