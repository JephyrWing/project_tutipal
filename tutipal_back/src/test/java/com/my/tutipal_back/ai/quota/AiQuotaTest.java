package com.my.tutipal_back.ai.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.my.tutipal_back.ai.client.AiUnavailableException;
import com.my.tutipal_back.common.exception.ApiException;

/** PT-97: per-user / per-feature sliding window quota. Fake clock only, no sleeps. */
class AiQuotaTest {

    private static final Instant T0 = Instant.parse("2026-11-25T00:00:00Z");
    private static final Duration HOUR = Duration.ofHours(1);
    private static final long A = 1L;
    private static final long B = 2L;

    private final MutableClock clock = new MutableClock(T0);

    private AiQuota quota(int limit) {
        return AiQuotaFixtures.quota(limit, HOUR, clock);
    }

    // B1
    @Test
    void rejectsThirdCallOfOneUserButNotAnotherUser() {
        AiQuota quota = quota(2);
        assertThat(quota.run(A, AiFeature.CHAT, () -> "1")).isEqualTo("1");
        assertThat(quota.run(A, AiFeature.CHAT, () -> "2")).isEqualTo("2");

        AiQuotaExceededException e = catchThrowableOfType(AiQuotaExceededException.class,
                () -> quota.reserve(A, AiFeature.CHAT));
        assertThat(e).isNotNull();
        assertThat(e.getFeature()).isEqualTo(AiFeature.CHAT);

        assertThat(quota.run(B, AiFeature.CHAT, () -> "other user")).isEqualTo("other user");
    }

    // B2
    @Test
    void fullFeatureDoesNotBlockOtherFeaturesOfSameUser() {
        AiQuota quota = quota(1);
        quota.run(A, AiFeature.SCREEN_AUTO, () -> "auto");
        assertThatThrownBy(() -> quota.reserve(A, AiFeature.SCREEN_AUTO))
                .isInstanceOf(AiQuotaExceededException.class);

        assertThat(quota.run(A, AiFeature.SCREEN_MANUAL, () -> "manual")).isEqualTo("manual");
        assertThat(quota.run(A, AiFeature.CHAT, () -> "chat")).isEqualTo("chat");
        assertThat(quota.run(A, AiFeature.QUIZ_GENERATE, () -> "quiz")).isEqualTo("quiz");
    }

    // B3
    @Test
    void slidingWindowRecoversOneSlotWhenOldestEntryExpires() {
        AiQuota quota = quota(2);
        quota.run(A, AiFeature.CHAT, () -> "t0");
        clock.set(T0.plus(Duration.ofMinutes(10)));
        quota.run(A, AiFeature.CHAT, () -> "t0+10m");

        clock.set(T0.plus(Duration.ofMinutes(20)));
        AiQuotaExceededException first = catchThrowableOfType(AiQuotaExceededException.class,
                () -> quota.reserve(A, AiFeature.CHAT));
        assertThat(first).isNotNull();
        assertThat(first.getRetryAt()).isEqualTo(T0.plus(HOUR));
        assertThat(first.getRetryAfterSeconds()).isEqualTo(40 * 60L);

        clock.set(T0.plus(HOUR));
        assertThat(quota.run(A, AiFeature.CHAT, () -> "recovered")).isEqualTo("recovered");

        AiQuotaExceededException second = catchThrowableOfType(AiQuotaExceededException.class,
                () -> quota.reserve(A, AiFeature.CHAT));
        assertThat(second).isNotNull();
        assertThat(second.getRetryAt()).isEqualTo(T0.plus(Duration.ofMinutes(10)).plus(HOUR));
    }

    // B4
    @Test
    void retryAfterSecondsRoundsUp() {
        AiQuota quota = quota(1);
        quota.run(A, AiFeature.CHAT, () -> "x");

        clock.set(T0.plus(HOUR).minusMillis(59_200));
        assertThat(rejection(quota).getRetryAfterSeconds()).isEqualTo(60L);

        clock.set(T0.plus(HOUR).minusMillis(300));
        assertThat(rejection(quota).getRetryAfterSeconds()).isEqualTo(1L);
    }

    @Test
    void waitingRetryAfterSecondsIsEnoughEvenWithSubMillisRemainder() {
        AiQuota quota = quota(1);
        quota.run(A, AiFeature.CHAT, () -> "x");

        clock.set(T0.plus(HOUR).minusSeconds(1).minusNanos(500_000));   // 1.0005 s left
        long wait = rejection(quota).getRetryAfterSeconds();
        assertThat(wait).isEqualTo(2L);

        clock.advance(Duration.ofSeconds(wait));
        assertThat(AiQuotaFixtures.hasRoom(quota, A, AiFeature.CHAT)).isTrue();
    }

    // B5
    @Test
    void refundReturnsCapacity() {
        AiQuota quota = quota(1);
        AiQuotaTicket ticket = quota.reserve(A, AiFeature.CHAT);
        assertThatThrownBy(() -> quota.reserve(A, AiFeature.CHAT)).isInstanceOf(AiQuotaExceededException.class);

        ticket.refund();

        assertThat(quota.run(A, AiFeature.CHAT, () -> "again")).isEqualTo("again");
    }

    @Test
    void unsettledReservationCountsAgainstLimit() {
        AiQuota quota = quota(2);
        quota.reserve(A, AiFeature.CHAT);
        quota.reserve(A, AiFeature.CHAT);
        assertThatThrownBy(() -> quota.reserve(A, AiFeature.CHAT)).isInstanceOf(AiQuotaExceededException.class);
    }

    @Test
    void refundAfterCommitIsNoOp() {
        AiQuota quota = quota(1);
        AiQuotaTicket ticket = quota.reserve(A, AiFeature.CHAT);
        ticket.commit();
        ticket.refund();
        assertThatThrownBy(() -> quota.reserve(A, AiFeature.CHAT)).isInstanceOf(AiQuotaExceededException.class);
    }

    @Test
    void doubleRefundDoesNotFreeAnotherEntry() {
        AiQuota quota = quota(2);
        AiQuotaTicket first = quota.reserve(A, AiFeature.CHAT);
        quota.reserve(A, AiFeature.CHAT);

        first.refund();
        first.refund();

        quota.reserve(A, AiFeature.CHAT);
        assertThatThrownBy(() -> quota.reserve(A, AiFeature.CHAT)).isInstanceOf(AiQuotaExceededException.class);
    }

    @Test
    void releaseIfUnusedRefundsWhenRunWasNeverCalled() {
        AiQuota quota = quota(1);
        quota.reserve(A, AiFeature.CHAT).releaseIfUnused();
        assertThat(quota.run(A, AiFeature.CHAT, () -> "ok")).isEqualTo("ok");
    }

    @Test
    void releaseIfUnusedAfterRunIsNoOp() {
        AiQuota quota = quota(1);
        AiQuotaTicket ticket = quota.reserve(A, AiFeature.CHAT);
        assertThat(ticket.run(() -> "answer")).isEqualTo("answer");

        ticket.releaseIfUnused();

        assertThatThrownBy(() -> quota.reserve(A, AiFeature.CHAT)).isInstanceOf(AiQuotaExceededException.class);
    }

    @Test
    void releaseIfUnusedAfterCountedFailureIsNoOp() {
        AiQuota quota = quota(1);
        AiQuotaTicket ticket = quota.reserve(A, AiFeature.CHAT);
        assertThatThrownBy(() -> ticket.run(() -> { throw new RuntimeException("client gone"); }))
                .hasMessage("client gone");

        ticket.releaseIfUnused();

        assertThatThrownBy(() -> quota.reserve(A, AiFeature.CHAT)).isInstanceOf(AiQuotaExceededException.class);
    }

    // Settlement rule
    @Test
    void aiUnavailableAfterStartIsRefundedAndRethrown() {
        AiQuota quota = quota(1);
        var down = new AiUnavailableException();
        assertThatThrownBy(() -> quota.run(A, AiFeature.CHAT, () -> { throw down; })).isSameAs(down);
        assertThat(AiQuotaFixtures.hasRoom(quota, A, AiFeature.CHAT)).isTrue();
    }

    @Test
    void aiUnavailableAfterModelOutputIsCountedAndRethrown() {
        AiQuota quota = quota(1);
        var timedOut = AiUnavailableException.afterOutput();
        assertThatThrownBy(() -> quota.run(A, AiFeature.CHAT, () -> { throw timedOut; })).isSameAs(timedOut);
        assertThat(AiQuotaFixtures.hasRoom(quota, A, AiFeature.CHAT)).isFalse();
    }

    @Test
    void slotWaitTimeout503IsRefundedAndRethrown() {
        AiQuota quota = quota(1);
        var busy = new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "busy");
        AiQuotaTicket ticket = quota.reserve(A, AiFeature.SCREEN_MANUAL);
        assertThatThrownBy(() -> ticket.run(() -> { throw busy; })).isSameAs(busy);
        assertThat(AiQuotaFixtures.hasRoom(quota, A, AiFeature.SCREEN_MANUAL)).isTrue();
    }

    @Test
    void badGateway502AfterStartIsCounted() {
        AiQuota quota = quota(1);
        var malformed = new ApiException(HttpStatus.BAD_GATEWAY, "malformed");
        assertThatThrownBy(() -> quota.run(A, AiFeature.QUIZ_GENERATE, () -> { throw malformed; })).isSameAs(malformed);
        assertThat(AiQuotaFixtures.hasRoom(quota, A, AiFeature.QUIZ_GENERATE)).isFalse();
    }

    @Test
    void otherRuntimeExceptionAfterStartIsCounted() {
        AiQuota quota = quota(1);
        var cancelled = new IllegalStateException("client cancelled");
        assertThatThrownBy(() -> quota.run(A, AiFeature.CHAT, () -> { throw cancelled; })).isSameAs(cancelled);
        assertThat(AiQuotaFixtures.hasRoom(quota, A, AiFeature.CHAT)).isFalse();
    }

    @Test
    void successIsCounted() {
        AiQuota quota = quota(1);
        assertThat(quota.run(A, AiFeature.CHAT, () -> 42)).isEqualTo(42);
        assertThat(AiQuotaFixtures.hasRoom(quota, A, AiFeature.CHAT)).isFalse();
    }

    @Test
    void rejectedRunDoesNotExecuteWork() {
        AiQuota quota = quota(1);
        quota.run(A, AiFeature.CHAT, () -> "first");
        var ran = new AtomicInteger();
        assertThatThrownBy(() -> quota.run(A, AiFeature.CHAT, ran::incrementAndGet))
                .isInstanceOf(AiQuotaExceededException.class);
        assertThat(ran).hasValue(0);
    }

    // B6
    @Test
    void concurrentReservationsNeverExceedLimit() throws Exception {
        AiQuota quota = quota(5);
        var ready = new CountDownLatch(50);
        var go = new CountDownLatch(1);
        var done = new CountDownLatch(50);
        var accepted = new AtomicInteger();
        var rejected = new AtomicInteger();
        var other = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 50; i++) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        quota.reserve(A, AiFeature.CHAT);
                        accepted.incrementAndGet();
                    } catch (AiQuotaExceededException e) {
                        rejected.incrementAndGet();
                    } catch (Throwable e) {
                        other.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(accepted).hasValue(5);
        assertThat(rejected).hasValue(45);
        assertThat(other).hasValue(0);
    }

    // B8
    @Test
    void evictExpiredDropsKeysWhoseEntriesAllExpired() {
        AiQuota quota = quota(2);
        quota.run(A, AiFeature.CHAT, () -> "a");
        quota.run(B, AiFeature.SCREEN_AUTO, () -> "b");
        assertThat(quota.trackedKeys()).isEqualTo(2);

        clock.set(T0.plus(HOUR));
        quota.evictExpired();

        assertThat(quota.trackedKeys()).isZero();
    }

    @Test
    void evictExpiredKeepsKeysWithLiveEntries() {
        AiQuota quota = quota(2);
        quota.run(A, AiFeature.CHAT, () -> "a");
        clock.set(T0.plus(Duration.ofMinutes(30)));
        quota.evictExpired();
        assertThat(quota.trackedKeys()).isEqualTo(1);
        assertThat(quota.run(A, AiFeature.CHAT, () -> "b")).isEqualTo("b");
        assertThatThrownBy(() -> quota.reserve(A, AiFeature.CHAT)).isInstanceOf(AiQuotaExceededException.class);
    }

    private static AiQuotaExceededException rejection(AiQuota quota) {
        AiQuotaExceededException e = catchThrowableOfType(AiQuotaExceededException.class,
                () -> quota.reserve(A, AiFeature.CHAT));
        assertThat(e).isNotNull();
        return e;
    }
}
