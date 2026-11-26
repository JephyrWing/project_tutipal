package com.my.tutipal_back.ai.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** PT-97: a ticket runs at most once, and a released ticket can no longer run uncounted work. */
class AiQuotaTicketTest {

    private static final long A = 1L;

    private final AiQuota quota = AiQuotaFixtures.quota(2, Duration.ofHours(1),
            new MutableClock(Instant.parse("2026-11-25T00:00:00Z")));

    @Test
    void secondRunOnSameTicketIsRejectedAndDoesNotExecute() {
        AiQuotaTicket ticket = quota.reserve(A, AiFeature.CHAT);
        ticket.run(() -> "first");
        var ran = new AtomicInteger();

        assertThatThrownBy(() -> ticket.run(ran::incrementAndGet)).isInstanceOf(IllegalStateException.class);

        assertThat(ran).hasValue(0);
    }

    @Test
    void runAfterReleaseIsRejectedSoNoWorkGoesUncounted() {
        AiQuotaTicket ticket = quota.reserve(A, AiFeature.CHAT);
        ticket.releaseIfUnused();
        var ran = new AtomicInteger();

        assertThatThrownBy(() -> ticket.run(ran::incrementAndGet)).isInstanceOf(IllegalStateException.class);

        assertThat(ran).hasValue(0);
        quota.reserve(A, AiFeature.CHAT);
        quota.reserve(A, AiFeature.CHAT);
        assertThatThrownBy(() -> quota.reserve(A, AiFeature.CHAT)).isInstanceOf(AiQuotaExceededException.class);
    }

    @Test
    void errorAfterStartIsCountedAndRethrown() {
        var oom = new StackOverflowError("deep");
        assertThatThrownBy(() -> quota.run(A, AiFeature.CHAT, () -> { throw oom; })).isSameAs(oom);
        quota.reserve(A, AiFeature.CHAT);
        assertThatThrownBy(() -> quota.reserve(A, AiFeature.CHAT)).isInstanceOf(AiQuotaExceededException.class);
    }

    @Test
    void exceededMessageNamesTheFeatureAndRoundsMinutesUp() {
        quota.reserve(A, AiFeature.SCREEN_MANUAL);
        quota.reserve(A, AiFeature.SCREEN_MANUAL);

        assertThatThrownBy(() -> quota.reserve(A, AiFeature.SCREEN_MANUAL))
                .hasMessage("화면 질문 한도를 다 썼어요. 약 60분 뒤에 다시 시도해 주세요");
    }
}
