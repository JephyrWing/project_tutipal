package com.my.tutipal_back.materials.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PT-86 eval harness guards (Gradle task ragEvalTest; not part of test/build): the sealed hash check aborts on a
 * changed query file, and a sealed row is scored once - retried only after a failure that persisted no result.
 */
class RagEvalGuardsTest {

    private static final String ROW = "sealed_char800-v1_cosine";

    @TempDir
    Path dir;

    private void sealedFiles(String queries) throws IOException {
        Files.writeString(dir.resolve("queries-sealed.jsonl"), queries, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("sealed.sha256"),
                RagEvalMain.sha256(queries.getBytes(StandardCharsets.UTF_8)) + "  queries-sealed.jsonl\n");
    }

    @Test
    void unchangedSealedFilePasses() throws IOException {
        sealedFiles("{\"id\":\"s-01\"}\n");

        assertThat(RagEvalMain.requireSealedUnchanged(dir)).containsEntry("match", true);
    }

    @Test
    void changedSealedFileAborts() throws IOException {
        sealedFiles("{\"id\":\"s-01\"}\n");
        Files.writeString(dir.resolve("queries-sealed.jsonl"), "{\"id\":\"s-01\"}\n{\"id\":\"tamper\"}\n");

        assertThatThrownBy(() -> RagEvalMain.requireSealedUnchanged(dir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sealed run aborted");
    }

    @Test
    void sealedRowIsScoredOnlyOnce() throws IOException {
        SealedRunGuard.begin(dir, ROW);
        Files.writeString(SealedRunGuard.results(dir, ROW), "{}\n");

        assertThat(SealedRunGuard.marker(dir, ROW)).exists();
        assertThatThrownBy(() -> SealedRunGuard.begin(dir, ROW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void markerWithoutFailureRecordIsNotRetried() throws IOException {
        SealedRunGuard.begin(dir, ROW);   // e.g. the process was killed: no .failed, no result

        assertThat(SealedRunGuard.retryAllowed(dir, ROW)).isFalse();
        assertThatThrownBy(() -> SealedRunGuard.begin(dir, ROW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failureWithoutPersistedResultAllowsOneRetryAndKeepsHistory() throws IOException {
        SealedRunGuard.begin(dir, ROW);
        SealedRunGuard.recordFailure(dir, ROW, new IllegalStateException("rerank down"), 4);

        assertThat(Files.readString(SealedRunGuard.failed(dir, ROW)))
                .contains("java.lang.IllegalStateException").contains("\"queries_scored_in_memory\":4")
                .contains("\"results_persisted\":false");
        assertThat(SealedRunGuard.retryAllowed(dir, ROW)).isTrue();

        SealedRunGuard.begin(dir, ROW);

        assertThat(SealedRunGuard.failed(dir, ROW)).doesNotExist();
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).anyMatch(n -> n.startsWith(ROW + ".failed-"));
        }
    }

    /** Runs begin() for the same row on two threads released together; returns how many succeeded. */
    private int concurrentBegins(String row) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 2; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        SealedRunGuard.begin(dir, row);
                        succeeded.incrementAndGet();
                    } catch (IllegalStateException | IOException expected) {
                        // the other starter won
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        return succeeded.get();
    }

    @Test
    void twoConcurrentStartsOfTheSameRowLetExactlyOneScore() throws Exception {
        for (int i = 0; i < 50; i++) {
            assertThat(concurrentBegins(ROW + "-" + i)).as("round %d", i).isEqualTo(1);
        }
    }

    @Test
    void twoConcurrentRetriesAfterAFailureLetExactlyOneScore() throws Exception {
        for (int i = 0; i < 30; i++) {
            String row = ROW + "-retry-" + i;
            SealedRunGuard.begin(dir, row);
            SealedRunGuard.recordFailure(dir, row, new IllegalStateException("rerank down"), 0);

            assertThat(concurrentBegins(row)).as("round %d", i).isEqualTo(1);
        }
    }

    @Test
    void failureAfterResultsWerePersistedIsNotRetried() throws IOException {
        SealedRunGuard.begin(dir, ROW);
        Files.writeString(SealedRunGuard.results(dir, ROW), "{}\n");
        SealedRunGuard.recordFailure(dir, ROW, new IOException("summary append failed"), 30);

        assertThat(SealedRunGuard.retryAllowed(dir, ROW)).isFalse();
    }
}
