package com.my.tutipal_back.learning.focus.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.my.tutipal_back.ai.quota.MutableClock;
import com.my.tutipal_back.learning.focus.FocusProperties;

/** PT-90 AC-4: one accepted submit per (user, session) every submit-min-interval (50 s), kept in memory. */
class FocusSubmitLimiterTest {

    private static final Instant NOW = Instant.parse("2026-11-26T03:15:42Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final FocusSubmitLimiter limiter = new FocusSubmitLimiter(FocusProperties.defaults(), clock);

    @Test
    void firstSubmitIsAllowed() {
        assertThat(limiter.tryAcquire(2L, 10L)).isTrue();
    }

    @Test
    void submitAfter49Point9SecondsIsRejected() {
        limiter.tryAcquire(2L, 10L);
        clock.advance(Duration.ofMillis(49_900));

        assertThat(limiter.tryAcquire(2L, 10L)).isFalse();
    }

    @Test
    void submitAfterExactly50SecondsIsAllowed() {
        limiter.tryAcquire(2L, 10L);
        clock.advance(Duration.ofSeconds(50));

        assertThat(limiter.tryAcquire(2L, 10L)).isTrue();
    }

    @Test
    void rejectedAttemptDoesNotExtendTheWait() {
        limiter.tryAcquire(2L, 10L);
        clock.advance(Duration.ofSeconds(30));
        assertThat(limiter.tryAcquire(2L, 10L)).isFalse();
        clock.advance(Duration.ofSeconds(20));

        assertThat(limiter.tryAcquire(2L, 10L)).isTrue();
    }

    @Test
    void differentUsersAndSessionsAreLimitedSeparately() {
        assertThat(limiter.tryAcquire(2L, 10L)).isTrue();

        assertThat(limiter.tryAcquire(3L, 10L)).isTrue();
        assertThat(limiter.tryAcquire(2L, 11L)).isTrue();
        assertThat(limiter.tryAcquire(2L, 10L)).isFalse();
    }

    @Test
    void userAndSessionIdsAreNotInterchangeable() {
        assertThat(limiter.tryAcquire(10L, 2L)).isTrue();

        assertThat(limiter.tryAcquire(2L, 10L)).isTrue();
    }

    @Test
    void cleanupRemovesOnlyEntriesOlderThanTheInterval() {
        limiter.tryAcquire(2L, 10L);
        clock.advance(Duration.ofSeconds(40));
        limiter.tryAcquire(3L, 10L);
        clock.advance(Duration.ofSeconds(20));   // first entry 60 s old, second 20 s old

        int removed = limiter.evictExpired();

        assertThat(removed).isEqualTo(1);
        assertThat(limiter.size()).isEqualTo(1);
        assertThat(limiter.tryAcquire(3L, 10L)).isFalse();
        assertThat(limiter.tryAcquire(2L, 10L)).isTrue();
    }

    @Test
    void cleanupOnAnEmptyLimiterRemovesNothing() {
        assertThat(limiter.evictExpired()).isZero();
        assertThat(limiter.size()).isZero();
    }
}
