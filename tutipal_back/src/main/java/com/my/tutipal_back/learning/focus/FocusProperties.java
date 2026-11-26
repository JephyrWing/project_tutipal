package com.my.tutipal_back.learning.focus;

import java.time.Duration;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * PT-90 app.focus.*: focus check settings.
 *
 * <ul>
 *   <li>consentVersion: the consent text version a student must have agreed to (1..20 chars).</li>
 *   <li>retentionDays / retentionBatchSize: samples and states of sessions that ended more than N days ago are
 *       deleted hourly, in batches.</li>
 *   <li>maxSampleAgeDays: upper bound for every session, ended or not: samples older than N days (minute_at) and
 *       states not updated for N days are deleted by the same hourly job (a session the teacher never closed has
 *       no ended_at). Default 31 = retention 30 days + one day of class.</li>
 *   <li>submitMinInterval: minimum time between two accepted submits of the same (user, session).</li>
 *   <li>staleAfter: a state older than this is shown as NOT_PARTICIPATING.</li>
 *   <li>zone: time zone for the model's hour and weekday features.</li>
 *   <li>predictTimeout: total wait for FastAPI /v1/focus/predict.</li>
 * </ul>
 * Invalid values stop the application from starting.
 */
@ConfigurationProperties("app.focus")
public record FocusProperties(
        @DefaultValue("2026-11-26") String consentVersion,
        @DefaultValue("30") int retentionDays,
        @DefaultValue("500") int retentionBatchSize,
        @DefaultValue("50s") Duration submitMinInterval,
        @DefaultValue("3m") Duration staleAfter,
        @DefaultValue("Asia/Seoul") ZoneId zone,
        @DefaultValue("2s") Duration predictTimeout,
        @DefaultValue("31") int maxSampleAgeDays) {

    private static final int MAX_VERSION_LENGTH = 20;
    static final int DEFAULT_MAX_SAMPLE_AGE_DAYS = 31;

    @ConstructorBinding
    public FocusProperties {
        if (consentVersion == null || consentVersion.isBlank() || consentVersion.length() > MAX_VERSION_LENGTH) {
            throw new IllegalArgumentException("app.focus.consent-version 은 1~20자여야 해요");
        }
        if (retentionDays < 1 || retentionBatchSize < 1) {
            throw new IllegalArgumentException("app.focus.retention-days 와 retention-batch-size 는 1 이상이어야 해요");
        }
        if (maxSampleAgeDays < 1) {
            throw new IllegalArgumentException("app.focus.max-sample-age-days 는 1 이상이어야 해요");
        }
        requirePositive(submitMinInterval, "submit-min-interval");
        requirePositive(staleAfter, "stale-after");
        requirePositive(predictTimeout, "predict-timeout");
        if (zone == null) {
            throw new IllegalArgumentException("app.focus.zone 이 필요해요");
        }
    }

    /** Same settings with the default max-sample-age-days (31). */
    public FocusProperties(String consentVersion, int retentionDays, int retentionBatchSize,
                           Duration submitMinInterval, Duration staleAfter, ZoneId zone, Duration predictTimeout) {
        this(consentVersion, retentionDays, retentionBatchSize, submitMinInterval, staleAfter, zone, predictTimeout,
                DEFAULT_MAX_SAMPLE_AGE_DAYS);
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("app.focus." + name + " 는 0보다 커야 해요");
        }
    }

    public static FocusProperties defaults() {
        return new FocusProperties("2026-11-26", 30, 500, Duration.ofSeconds(50), Duration.ofMinutes(3),
                ZoneId.of("Asia/Seoul"), Duration.ofSeconds(2), DEFAULT_MAX_SAMPLE_AGE_DAYS);
    }
}
