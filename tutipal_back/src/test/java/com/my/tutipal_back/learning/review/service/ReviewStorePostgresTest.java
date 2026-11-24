package com.my.tutipal_back.learning.review.service;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.learning.review.repository.ReviewStore;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Dedicated task only. No env files, production DB, external model or new dependencies. */
class ReviewStorePostgresTest {
    static final Instant NOW = Instant.parse("2026-11-24T00:00:00Z");
    static JdbcTemplate jdbc;
    ReviewStore store;
    ReviewService service;
    static String vector() {
        float[] v = new float[1024]; v[0] = 1;
        return AiClient.pgVector(v);
    }

    @BeforeAll static void prepareDisposableDatabase() {
        String url = System.getenv("REVIEW_TEST_DB_URL");
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/tutipal_review_e2e")
                || !"disposable".equals(System.getenv("REVIEW_TEST_DB_ALLOW_RESET"))) {
            throw new IllegalStateException("Provide an explicit loopback tutipal_review_e2e disposable database");
        }
        String password = System.getenv("REVIEW_TEST_DB_PASSWORD");
        if (password == null || password.isBlank()) throw new IllegalStateException("Missing disposable DB password");
        var ds = new DriverManagerDataSource(url, "tutipal_review_e2e", password);
        Flyway.configure().dataSource(ds).target("14").load().migrate();
        jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO users(id,created_at,updated_at,name,provider,provider_id,role) VALUES "
                + "(101,now(),now(),'synthetic owner','dev','review-owner','USER'),"
                + "(202,now(),now(),'synthetic other','dev','review-other','USER') ON CONFLICT DO NOTHING");
        // Verify the forward migration preserves existing user/record rows.
        jdbc.update("INSERT INTO review_items(user_id,summary,embedding,embedding_model,seen_count,seen_seconds,first_seen,last_seen) "
                + "VALUES (101,'migration preservation',?::vector,'synthetic:1024',1,40,now(),now())", vector());
        long priorCount = jdbc.queryForObject("SELECT count(*) FROM review_items", Long.class);
        Flyway.configure().dataSource(ds).load().migrate();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_items", Long.class)).isEqualTo(priorCount);
        assertThat(jdbc.queryForObject("SELECT review_generation FROM users WHERE id=101", Long.class)).isZero();
    }

    @BeforeEach void setup() {
        jdbc.update("DELETE FROM review_items");
        store = new ReviewStore(jdbc);
        service = new ReviewService(store, mock(AiClient.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    long insert(long user, int seconds) {
        return store.insert(user, "합성 복습", vector(), "synthetic:1024", seconds, NOW);
    }

    @Test void deletionDuringEmbeddingCannotRecreateRecordsAndFreshObservationWorks() throws Exception {
        checkDeletionDuringEmbedding(false);
    }

    @Test void individualDeletionDuringEmbeddingAlsoInvalidatesOldObservation() throws Exception {
        checkDeletionDuringEmbedding(true);
    }

    private void checkDeletionDuringEmbedding(boolean individual) throws Exception {
        long id = insert(101, 600);
        long other = insert(202, 80);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        AiClient ai = mock(AiClient.class);
        when(ai.embedWithModel(any())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test release missing");
            float[] v = new float[1024]; v[0] = 1;
            return new AiClient.EmbedResponse("synthetic:1024", List.of(v));
        });
        var observer = new ReviewService(store, ai, Clock.fixed(NOW, ZoneOffset.UTC));
        try (var pool = Executors.newSingleThreadExecutor()) {
            Future<ReviewService.Observed> pending = pool.submit(() -> observer.observe(101L, "늦은 합성 요약", 40));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                if (individual) service.delete(101L, id); else service.deleteAll(101L);
            } finally { release.countDown(); }
            assertThat(pending.get(10, TimeUnit.SECONDS)).isEqualTo(new ReviewService.Observed(false, null));
        }
        assertThat(store.list(101L)).isEmpty();
        assertThat(store.find(202L, other)).isPresent();
        assertThat(observer.observe(101L, "새 합성 요약", 0).recorded()).isTrue();
        assertThat(store.list(101L)).hasSize(1);
    }

    @Test void staleTimeOnlyObservationIsRejectedAndOtherUserGenerationIsIndependent() {
        long old = store.generation(101L);
        long other = store.generation(202L);
        insert(101, 40); insert(202, 80);
        service.deleteAll(101L);
        assertThat(store.recordIfCurrent(101L, old, () -> { throw new AssertionError("stale write ran"); }, false)).isFalse();
        assertThat(store.recordIfCurrent(202L, other, () -> true, false)).isTrue();
    }

    @Test void deleteWaitsForAlreadyCommittingObservationThenRemovesItsRow() throws Exception {
        long generation = store.generation(101L);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var observing = pool.submit(() -> store.recordIfCurrent(101L, generation, () -> {
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("release missing"); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                return insert(101, 40);
            }, -1L));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var deleting = pool.submit(() -> { service.deleteAll(101L); return true; });
            try { assertThatThrownBy(() -> deleting.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { release.countDown(); }
            assertThat(observing.get(10, TimeUnit.SECONDS)).isPositive();
            assertThat(deleting.get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(store.list(101L)).isEmpty();
    }

    @Test void concurrentObserversOfSameSnapshotSuggestOnlyOnce() throws Exception {
        long id = insert(101, 600);
        var snapshot = store.get(id);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            List<Future<ReviewService.Suggestion>> results = IntStream.range(0, 8).mapToObj(n -> pool.submit(() -> {
                start.await(); return service.suggestIfDue(snapshot, NOW);
            })).toList();
            start.countDown();
            int suggested = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS) != null) suggested++;
            assertThat(suggested).isEqualTo(1);
        }
        assertThat(store.get(id).suggestedAt()).isEqualTo(NOW);
    }

    @Test void staleSnapshotAfterDeletionCannotSuggest() {
        long id = insert(101, 600);
        var snapshot = store.get(id);
        store.delete(101L, id);
        assertThat(service.suggestIfDue(snapshot, NOW)).isNull();
    }

    @Test void foreignGetAndDeletePreserveOwnerAndOwnDeleteWorks() {
        long owner = insert(101, 40);
        long other = insert(202, 80);
        assertThat(store.find(202L, owner)).isEmpty();
        assertThat(store.delete(202L, owner)).isZero();
        assertThat(store.find(101L, owner)).isPresent();
        assertThat(store.delete(101L, owner)).isEqualTo(1);
        assertThat(store.find(101L, owner)).isEmpty();
        assertThat(store.find(202L, other)).isPresent();
    }

    @Test void deleteAllOnlyDeletesOwnRowsAndIsIdempotent() {
        insert(101, 40); insert(101, 80); long other = insert(202, 90);
        assertThat(store.deleteAll(101L)).isEqualTo(2);
        assertThat(store.deleteAll(101L)).isZero();
        assertThat(store.list(101L)).isEmpty();
        assertThat(store.find(202L, other)).isPresent();
    }

    @Test void nearestIsolatesUserModelAndTimeWindow() {
        long own = insert(101, 40); insert(202, 40);
        assertThat(store.nearest(101L, vector(), "synthetic:1024", NOW.minus(Duration.ofDays(7)), 0.7))
                .hasValueSatisfying(item -> assertThat(item.id()).isEqualTo(own));
        assertThat(store.nearest(101L, vector(), "different-model", NOW.minusSeconds(1), 0.7)).isEmpty();
        assertThat(store.nearest(303L, vector(), "synthetic:1024", NOW.minusSeconds(1), 0.7)).isEmpty();
        assertThat(store.nearest(101L, vector(), "synthetic:1024", NOW.plusSeconds(1), 0.7)).isEmpty();
    }

    @Test void storedCountersReachThresholdsAndTimeDoesNotIncrementCount() {
        long id = insert(101, 599);
        assertThat(service.suggestIfDue(store.get(id), NOW)).isNull();
        store.touch(id, 0, 1, NOW, NOW.minus(Duration.ofDays(7)));
        assertThat(store.get(id).seenCount()).isEqualTo(1);
        assertThat(service.suggestIfDue(store.get(id), NOW)).isNotNull();
        long repeated = insert(101, 0);
        store.touch(repeated, 1, 0, NOW, NOW.minus(Duration.ofDays(7)));
        assertThat(service.suggestIfDue(store.get(repeated), NOW)).isNull();
        store.touch(repeated, 1, 0, NOW, NOW.minus(Duration.ofDays(7)));
        assertThat(service.suggestIfDue(store.get(repeated), NOW)).isNotNull();
    }

    @Test void oldApproximateWindowResetsCountersButKeepsOtherUser() {
        long id = store.insert(101L, "오래된 합성 복습", vector(), "synthetic:1024", 600, NOW.minus(Duration.ofDays(8)));
        long other = insert(202, 40);
        store.touch(id, 0, 40, NOW, NOW.minus(Duration.ofDays(7)));
        var item = store.get(id);
        assertThat(item.seenCount()).isEqualTo(1);
        assertThat(item.seenSeconds()).isEqualTo(40);
        assertThat(item.firstSeen()).isEqualTo(NOW);
        assertThat(service.suggestIfDue(item, NOW)).isNull();
        assertThat(store.get(other).seenSeconds()).isEqualTo(40);
    }

    @Test void exactlySevenDayWindowIsKept() {
        long id = store.insert(101L, "7일 경계", vector(), "synthetic:1024", 100, NOW.minus(Duration.ofDays(7)));
        store.touch(id, 1, 40, NOW, NOW.minus(Duration.ofDays(7)));
        assertThat(store.get(id).seenCount()).isEqualTo(2);
        assertThat(store.get(id).seenSeconds()).isEqualTo(140);
    }

    @Test void reSuggestionIsAllowedExactlyAfter24Hours() {
        long id = insert(101, 600);
        assertThat(service.suggestIfDue(store.get(id), NOW)).isNotNull();
        assertThat(service.suggestIfDue(store.get(id), NOW.plusSeconds(86399))).isNull();
        assertThat(service.suggestIfDue(store.get(id), NOW.plusSeconds(86400))).isNotNull();
    }

    @Test void resetCountersInvalidateAnOlderEligibleSnapshot() {
        long id = store.insert(101L, "초기화 전", vector(), "synthetic:1024", 600, NOW.minus(Duration.ofDays(8)));
        var snapshot = store.get(id);
        store.touch(id, 0, 40, NOW, NOW.minus(Duration.ofDays(7)));
        assertThat(service.suggestIfDue(snapshot, NOW)).isNull();
    }
}
