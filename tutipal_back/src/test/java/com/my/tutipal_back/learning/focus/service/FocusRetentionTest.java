package com.my.tutipal_back.learning.focus.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import com.my.tutipal_back.ai.quota.MutableClock;
import com.my.tutipal_back.learning.focus.FocusProperties;
import com.my.tutipal_back.learning.focus.FocusState;
import com.my.tutipal_back.learning.focus.repository.FakeFocusStore;
import com.my.tutipal_back.learning.focus.repository.FocusStore;

/** PT-90 AC-9: the hourly job deletes samples and states of sessions that ended more than 30 days ago, in batches. */
class FocusRetentionTest {

    private static final Instant NOW = Instant.parse("2026-11-26T03:00:00Z");
    private static final Instant CUTOFF = NOW.minus(Duration.ofDays(30));

    private final MutableClock clock = new MutableClock(NOW);

    private static FocusProperties batch(int size) {
        FocusProperties d = FocusProperties.defaults();
        return new FocusProperties(d.consentVersion(), d.retentionDays(), size, d.submitMinInterval(),
                d.staleAfter(), d.zone(), d.predictTimeout());
    }

    @Test
    void defaultsAre30DaysAndBatchesOf500() {
        FocusProperties d = FocusProperties.defaults();

        assertThat(d.retentionDays()).isEqualTo(30);
        assertThat(d.retentionBatchSize()).isEqualTo(500);
        assertThat(d.zone()).isEqualTo(ZoneId.of("Asia/Seoul"));
    }

    @Test
    void deletesInBatchesUntilAShortBatch() {
        FocusStore store = mock(FocusStore.class);
        when(store.deleteExpiredSamples(any(), anyInt())).thenReturn(500, 500, 7);
        when(store.deleteExpiredStates(any(), anyInt())).thenReturn(3);

        int deleted = new FocusRetention(store, FocusProperties.defaults(), clock).purgeExpired();

        assertThat(deleted).isEqualTo(1010);
        verify(store, times(3)).deleteExpiredSamples(eq(CUTOFF), eq(500));
        verify(store, times(1)).deleteExpiredStates(eq(CUTOFF), eq(500));
    }

    @Test
    void onlySessionsEndedBeforeTheCutoffArePurged() {
        FakeFocusStore store = new FakeFocusStore();
        store.seedEndedSession(1L, NOW.minus(Duration.ofDays(31)));
        store.seedEndedSession(2L, NOW.minus(Duration.ofDays(29)));
        for (long session : new long[] {1L, 2L, 3L}) {   // 3 has not ended
            store.seedSample(session, 7L, new FocusStore.Sample(NOW.minus(Duration.ofDays(40)), "code.exe", "ide", 1, 1));
            store.seedSample(session, 7L, new FocusStore.Sample(NOW.minus(Duration.ofDays(39)), "code.exe", "ide", 1, 1));
            store.seedState(session, 7L, FocusState.FOCUS, NOW.minus(Duration.ofDays(39)));
        }

        int deleted = new FocusRetention(store, batch(1), clock).purgeExpired();

        assertThat(deleted).isEqualTo(3);
        assertThat(store.samplesOf(1L, 7L)).isEmpty();
        assertThat(store.storedState(1L, 7L)).isEmpty();
        assertThat(store.samplesOf(2L, 7L)).hasSize(2);
        assertThat(store.storedState(2L, 7L)).isPresent();
        assertThat(store.samplesOf(3L, 7L)).hasSize(2);
        assertThat(store.storedState(3L, 7L)).isPresent();
    }

    @Test
    void hourlyJobSwallowsFailures() {
        FocusStore store = mock(FocusStore.class);
        when(store.deleteExpiredSamples(any(), anyInt())).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> new FocusRetention(store, FocusProperties.defaults(), clock).scheduledPurge())
                .doesNotThrowAnyException();
    }

    @Test
    void hourlyJobRunsThePurge() {
        FocusStore store = mock(FocusStore.class);

        new FocusRetention(store, FocusProperties.defaults(), clock).scheduledPurge();

        verify(store).deleteExpiredSamples(eq(CUTOFF), eq(500));
        verify(store).deleteExpiredStates(eq(CUTOFF), eq(500));
    }

    // ------------------------------------------------------------------ age upper bound (app.focus.max-sample-age-days)

    @Test
    void maxSampleAgeDefaultsTo31Days() {
        assertThat(FocusProperties.defaults().maxSampleAgeDays()).isEqualTo(31);
    }

    @Test
    void oldRowsOfASessionThatNeverEndedAreDeletedByAge() {
        FakeFocusStore store = new FakeFocusStore();
        long running = 3L;   // the teacher never closed it: no ended_at
        store.seedSample(running, 7L, new FocusStore.Sample(NOW.minus(Duration.ofDays(40)), "code.exe", "ide", 1, 1));
        store.seedSample(running, 7L, new FocusStore.Sample(NOW.minus(Duration.ofDays(32)), "code.exe", "ide", 1, 1));
        store.seedSample(running, 7L, new FocusStore.Sample(NOW.minus(Duration.ofDays(30)), "code.exe", "ide", 1, 1));
        store.seedState(running, 7L, FocusState.FOCUS, NOW.minus(Duration.ofDays(32)));
        store.seedState(running, 8L, FocusState.FOCUS, NOW.minus(Duration.ofDays(1)));

        int deleted = new FocusRetention(store, batch(1), clock).purgeStale();

        assertThat(deleted).isEqualTo(3);
        assertThat(store.samplesOf(running, 7L)).extracting(FocusStore.Sample::minuteAt)
                .containsExactly(NOW.minus(Duration.ofDays(30)));
        assertThat(store.storedState(running, 7L)).isEmpty();
        assertThat(store.storedState(running, 8L)).isPresent();
    }

    @Test
    void ageBoundUsesTheConfiguredDaysInBatches() {
        FocusStore store = mock(FocusStore.class);
        when(store.deleteStaleSamples(any(), anyInt())).thenReturn(500, 2);
        FocusProperties d = FocusProperties.defaults();
        FocusProperties tenDays = new FocusProperties(d.consentVersion(), d.retentionDays(), d.retentionBatchSize(),
                d.submitMinInterval(), d.staleAfter(), d.zone(), d.predictTimeout(), 10);

        int deleted = new FocusRetention(store, tenDays, clock).purgeStale();

        assertThat(deleted).isEqualTo(502);
        verify(store, times(2)).deleteStaleSamples(eq(NOW.minus(Duration.ofDays(10))), eq(500));
        verify(store).deleteStaleStates(eq(NOW.minus(Duration.ofDays(10))), eq(500));
    }

    @Test
    void hourlyJobAlsoRunsTheAgeBound() {
        FocusStore store = mock(FocusStore.class);

        new FocusRetention(store, FocusProperties.defaults(), clock).scheduledPurge();

        verify(store).deleteStaleSamples(eq(NOW.minus(Duration.ofDays(31))), eq(500));
        verify(store).deleteStaleStates(eq(NOW.minus(Duration.ofDays(31))), eq(500));
    }
}
