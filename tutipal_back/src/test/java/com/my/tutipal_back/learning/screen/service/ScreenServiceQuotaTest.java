package com.my.tutipal_back.learning.screen.service;

import static com.my.tutipal_back.ai.quota.AiQuotaFixtures.hasRoom;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.ai.client.AiSlots;
import com.my.tutipal_back.ai.client.AiUnavailableException;
import com.my.tutipal_back.ai.quota.AiFeature;
import com.my.tutipal_back.ai.quota.AiQuota;
import com.my.tutipal_back.ai.quota.AiQuotaExceededException;
import com.my.tutipal_back.ai.quota.AiQuotaFixtures;
import com.my.tutipal_back.ai.quota.MutableClock;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.learning.screen.dto.ScreenRequest;

/** PT-97 S1/S2/S6: screen summary vs question quota, refund vs counted. */
class ScreenServiceQuotaTest {

    static final String PNG = Base64.getEncoder().encodeToString(
            new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13});
    private static final long USER = 2L;

    private final AiClient ai = mock(AiClient.class);
    private final AiSlots slots = mock(AiSlots.class);
    private final AiQuota quota = AiQuotaFixtures.quota(1, Duration.ofHours(1),
            new MutableClock(Instant.parse("2026-11-25T00:00:00Z")));
    private final ScreenService service = new ScreenService(ai, slots, quota);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void slotsRunTheWork() {
        when(slots.run(any(Supplier.class))).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
        when(slots.run(any(Duration.class), any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        when(ai.complete(anyString(), anyList(), anyBoolean())).thenReturn("  화면 설명  ");
    }

    // S1
    @Test
    void summaryWithoutQuestionUsesScreenAuto() {
        assertThat(service.describe(USER, new ScreenRequest(PNG, null))).isEqualTo("화면 설명");

        assertThat(hasRoom(quota, USER, AiFeature.SCREEN_AUTO)).isFalse();
        assertThat(hasRoom(quota, USER, AiFeature.SCREEN_MANUAL)).isTrue();
    }

    @Test
    void blankQuestionCountsAsScreenAuto() {
        service.describe(USER, new ScreenRequest(PNG, "   "));

        assertThat(hasRoom(quota, USER, AiFeature.SCREEN_AUTO)).isFalse();
        assertThat(hasRoom(quota, USER, AiFeature.SCREEN_MANUAL)).isTrue();
    }

    @Test
    void questionUsesScreenManual() {
        assertThat(service.describe(USER, new ScreenRequest(PNG, "이 식은 뭐야?"))).isEqualTo("화면 설명");

        assertThat(hasRoom(quota, USER, AiFeature.SCREEN_MANUAL)).isFalse();
        assertThat(hasRoom(quota, USER, AiFeature.SCREEN_AUTO)).isTrue();
    }

    @Test
    void invalidImageIsRejectedWithoutReservation() {
        assertThatThrownBy(() -> service.describe(USER, new ScreenRequest("bm90IGFuIGltYWdl", "질문")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.describe(USER, new ScreenRequest("bm90IGFuIGltYWdl", null)))
                .isInstanceOf(ApiException.class);

        assertThat(hasRoom(quota, USER, AiFeature.SCREEN_MANUAL)).isTrue();
        assertThat(hasRoom(quota, USER, AiFeature.SCREEN_AUTO)).isTrue();
        verifyNoInteractions(ai);
    }

    // S2
    @Test
    @SuppressWarnings("unchecked")
    void slotWaitTimeoutIsRefunded() {
        var busy = new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "지금 질문이 많아요");
        when(slots.run(any(Supplier.class))).thenThrow(busy);
        when(slots.run(any(Duration.class), any(Supplier.class))).thenThrow(busy);

        assertThatThrownBy(() -> service.describe(USER, new ScreenRequest(PNG, null))).isSameAs(busy);
        assertThatThrownBy(() -> service.describe(USER, new ScreenRequest(PNG, "질문"))).isSameAs(busy);

        assertThat(hasRoom(quota, USER, AiFeature.SCREEN_AUTO)).isTrue();
        assertThat(hasRoom(quota, USER, AiFeature.SCREEN_MANUAL)).isTrue();
    }

    @Test
    void aiUnavailableIsRefunded() {
        when(ai.complete(anyString(), anyList(), anyBoolean())).thenThrow(new AiUnavailableException());

        assertThatThrownBy(() -> service.describe(USER, new ScreenRequest(PNG, "질문")))
                .isInstanceOf(AiUnavailableException.class);

        assertThat(hasRoom(quota, USER, AiFeature.SCREEN_MANUAL)).isTrue();
    }

    @Test
    void aiFailureAfterModelOutputIsCounted() {
        when(ai.complete(anyString(), anyList(), anyBoolean())).thenThrow(AiUnavailableException.afterOutput());

        assertThatThrownBy(() -> service.describe(USER, new ScreenRequest(PNG, "질문")))
                .isInstanceOf(AiUnavailableException.class);

        assertThat(hasRoom(quota, USER, AiFeature.SCREEN_MANUAL)).isFalse();
    }

    @Test
    void successIsCounted() {
        service.describe(USER, new ScreenRequest(PNG, "질문"));
        assertThat(hasRoom(quota, USER, AiFeature.SCREEN_MANUAL)).isFalse();
    }

    // S6
    @Test
    void rejectedRequestNeverReachesSlotsOrModel() {
        quota.reserve(USER, AiFeature.SCREEN_MANUAL);

        assertThatThrownBy(() -> service.describe(USER, new ScreenRequest(PNG, "질문")))
                .isInstanceOfSatisfying(AiQuotaExceededException.class,
                        e -> assertThat(e.getFeature()).isEqualTo(AiFeature.SCREEN_MANUAL));

        verifyNoInteractions(ai);
        verify(slots, never()).run(any(Supplier.class));
        verify(slots, never()).run(any(Duration.class), any(Supplier.class));
    }
}
