package com.my.tutipal_back.learning.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.learning.review.repository.ReviewStore;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock
    private ReviewStore store;

    @Mock
    private AiClient ai;

    @Test
    void recordsAnObservationForTheSignedInUserWithoutAStoredPreference() {
        long userId = 7L;
        long itemId = 11L;
        String summary = "반복문 개념";
        Instant now = Instant.parse("2026-11-23T06:00:00Z");
        float[] embedding = {1.0f, 0.0f};
        String vector = AiClient.pgVector(embedding);
        ReviewService service = new ReviewService(store, ai, Clock.fixed(now, ZoneOffset.UTC));

        when(ai.embedWithModel(List.of(summary)))
                .thenReturn(new AiClient.EmbedResponse("test-model", List.of(embedding)));
        when(store.nearest(userId, vector, "test-model", now.minus(ReviewService.WINDOW), ReviewService.SIMILAR))
                .thenReturn(Optional.empty());
        when(store.insert(userId, summary, vector, "test-model", 40, now)).thenReturn(itemId);
        when(store.get(itemId)).thenReturn(new ReviewStore.Item(itemId, summary, 1, 40, now, now, null));

        assertThat(service.observe(userId, summary, 40)).isEqualTo(new ReviewService.Observed(true, null));
    }
}
