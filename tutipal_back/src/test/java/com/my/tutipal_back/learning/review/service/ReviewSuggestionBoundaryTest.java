package com.my.tutipal_back.learning.review.service;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.learning.review.repository.ReviewStore;
import com.my.tutipal_back.learning.review.repository.ReviewStore.Item;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReviewSuggestionBoundaryTest {
    static final Instant NOW = Instant.parse("2026-11-24T00:00:00Z");
    ReviewStore store;
    AiClient ai;
    ReviewService service;

    @BeforeEach void setup() {
        store = mock(ReviewStore.class);
        ai = mock(AiClient.class);
        service = new ReviewService(store, ai, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(store.markSuggestedIfDue(11L, NOW, NOW.minus(Duration.ofDays(1)), 3, 600)).thenReturn(true);
    }

    Item item(int count, int seconds, Instant lastSeen, Instant suggestedAt) {
        return new Item(11L, "반복문", count, seconds, NOW.minusSeconds(1000), lastSeen, suggestedAt);
    }

    @ParameterizedTest
    @CsvSource({"2,599,false", "3,0,true", "1,600,true", "2,600,true", "3,599,true", "1,0,false"})
    void countOrTimeThreshold(int count, int seconds, boolean due) {
        assertThat(service.suggestIfDue(item(count, seconds, NOW, null), NOW) != null).isEqualTo(due);
    }

    @ParameterizedTest
    @CsvSource({"86399,false", "86400,true", "86401,true", "0,false"})
    void elapsed24HoursBoundary(long elapsed, boolean due) {
        assertThat(service.suggestIfDue(item(3, 600, NOW, NOW.minusSeconds(elapsed)), NOW) != null).isEqualTo(due);
    }

    @ParameterizedTest
    @CsvSource({"179,true", "180,true", "181,false"})
    void unchangedScreenRequiresRecentObservation(int age, boolean recorded) {
        when(store.latest(7L)).thenReturn(Optional.of(item(1, 0, NOW.minusSeconds(age), null)));
        if (recorded) when(store.get(11L)).thenReturn(item(1, 40, NOW, null));
        assertThat(service.observe(7L, null, 40).recorded()).isEqualTo(recorded);
        verifyNoInteractions(ai);
        if (!recorded) verify(store, never()).touch(anyLong(), anyInt(), anyInt(), any(), any());
    }

    @ParameterizedTest
    @CsvSource({"-1,0", "121,120"})
    void clampsClientTime(int input, int expected) {
        when(store.latest(7L)).thenReturn(Optional.of(item(1, 0, NOW, null)));
        when(store.get(11L)).thenReturn(item(1, expected, NOW, null));
        assertThat(service.observe(7L, "", input).recorded()).isTrue();
        verify(store).touch(11L, 0, expected, NOW, NOW.minus(Duration.ofDays(7)));
    }

    @Test void noPriorScreenDoesNotCreateARecord() {
        when(store.latest(7L)).thenReturn(Optional.empty());
        assertThat(service.observe(7L, null, 40)).isEqualTo(new ReviewService.Observed(false, null));
        verifyNoInteractions(ai);
    }

    @Test void embeddingFailureDoesNotBreakWidgetOrCreateARecord() {
        when(ai.embedWithModel(anyList())).thenThrow(new IllegalStateException("synthetic failure"));
        assertThat(service.observe(7L, "새 화면", 40)).isEqualTo(new ReviewService.Observed(false, null));
        verifyNoInteractions(store);
    }

    @Test void repositoryFailureIsReportedAsNotRecorded() {
        when(store.latest(7L)).thenThrow(new IllegalStateException("synthetic failure"));
        assertThat(service.observe(7L, null, 40)).isEqualTo(new ReviewService.Observed(false, null));
    }

    @Test void lostDatabaseClaimDoesNotReturnSuggestion() {
        when(store.markSuggestedIfDue(11L, NOW, NOW.minus(Duration.ofDays(1)), 3, 600)).thenReturn(false);
        assertThat(service.suggestIfDue(item(3, 600, NOW, null), NOW)).isNull();
    }
}
