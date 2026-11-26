package com.my.tutipal_back.learning.insights.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.my.tutipal_back.ai.quota.MutableClock;
import com.my.tutipal_back.learning.insights.dto.ConfusionsResponse;

/** PT-87 D8: in-memory result cache, at most 100 entries, 10 minute TTL, key includes the inputs and the model. */
class TopicResultCacheTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-25T00:00:00Z"));
    private final TopicResultCache cache = new TopicResultCache(clock);

    private static TopicResultCache.Key key(long sessionId) {
        return new TopicResultCache.Key(sessionId, 40L, 22, "concepts-v1", "ollama:bge-m3:1024");
    }

    private static ConfusionsResponse result(int questions) {
        return new ConfusionsResponse("OK", null, questions, 6, "kmeans-silhouette-v1", 3, 0.4, false,
                List.of(new ConfusionsResponse.Topic(1, "변수", 100L, 4, 3)));
    }

    @Test
    void storedResultIsReturnedForTheSameKey() {
        cache.put(key(10L), result(22));

        assertThat(cache.get(key(10L))).hasValueSatisfying(r -> assertThat(r.questionCount()).isEqualTo(22));
    }

    @Test
    void anyKeyPartChangeIsAMiss() {
        cache.put(key(10L), result(22));

        assertThat(cache.get(key(11L))).isEmpty();
        assertThat(cache.get(new TopicResultCache.Key(10L, 41L, 22, "concepts-v1", "ollama:bge-m3:1024"))).isEmpty();
        assertThat(cache.get(new TopicResultCache.Key(10L, 40L, 23, "concepts-v1", "ollama:bge-m3:1024"))).isEmpty();
        assertThat(cache.get(new TopicResultCache.Key(10L, 40L, 22, "concepts-v2", "ollama:bge-m3:1024"))).isEmpty();
        assertThat(cache.get(new TopicResultCache.Key(10L, 40L, 22, "concepts-v1", "other-model"))).isEmpty();
    }

    @Test
    void entryExpiresAfterTenMinutes() {
        cache.put(key(10L), result(22));

        clock.advance(Duration.ofMinutes(9).plusSeconds(59));
        assertThat(cache.get(key(10L))).isPresent();

        clock.advance(Duration.ofSeconds(2));
        assertThat(cache.get(key(10L))).isEmpty();
    }

    @Test
    void holdsAtMostOneHundredEntriesAndKeepsTheNewest() {
        for (long session = 1; session <= 101; session++) {
            cache.put(key(session), result((int) session));
            clock.advance(Duration.ofMillis(1));
        }

        long hits = 0;
        for (long session = 1; session <= 101; session++) {
            if (cache.get(key(session)).isPresent()) {
                hits++;
            }
        }
        assertThat(hits).isLessThanOrEqualTo(100);
        assertThat(cache.get(key(101L))).isPresent();
    }
}
