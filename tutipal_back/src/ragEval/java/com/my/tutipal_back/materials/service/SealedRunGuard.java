package com.my.tutipal_back.materials.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.json.JsonMapper;

/**
 * PT-86 "sealed is scored once" bookkeeping (docs/PT-86_RAG_EVAL.md 1).
 * <ul>
 *   <li>{@code <row>.done} is written only after every preflight check passed (hash, frozen decisions, cache
 *       completeness, rerank endpoint), immediately before the first sealed query is scored.</li>
 *   <li>If the run then fails, {@code <row>.failed} records time, exception class, how many queries were scored in
 *       memory and whether any result was persisted.</li>
 *   <li>A row with a {@code .done} marker may be retried only when no result file exists and the last failure
 *       persisted no scores. Results are written once at the end of a run, so "persisted" = the result file exists.</li>
 * </ul>
 */
final class SealedRunGuard {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SealedRunGuard() {
    }

    static Path marker(Path resultDir, String rowId) {
        return resultDir.resolve(rowId + ".done");
    }

    static Path failed(Path resultDir, String rowId) {
        return resultDir.resolve(rowId + ".failed");
    }

    static Path results(Path resultDir, String rowId) {
        return resultDir.resolve(rowId + ".jsonl");
    }

    /**
     * Throws when the row must not be scored again; otherwise creates the marker (call after preflight).
     * Atomic against concurrent starts of the same row: the marker is created with CREATE_NEW, and a retry is claimed
     * by atomically moving the failure record (then the old marker) away - only one process can win each step.
     */
    static void begin(Path resultDir, String rowId) throws IOException {
        Path marker = marker(resultDir, rowId);
        if (Files.exists(marker)) {
            if (!retryAllowed(resultDir, rowId)) {
                throw alreadyStarted(rowId);
            }
            String suffix = System.currentTimeMillis() + "-" + UUID.randomUUID();
            try {   // keep the failure history; the process that moves it owns the retry
                Files.move(failed(resultDir, rowId), resultDir.resolve(rowId + ".failed-" + suffix),
                        StandardCopyOption.ATOMIC_MOVE);
                Files.move(marker, resultDir.resolve(rowId + ".done-" + suffix), StandardCopyOption.ATOMIC_MOVE);
            } catch (FileSystemException e) {   // gone (NoSuchFile) or held by the other starter (Windows)
                throw alreadyStarted(rowId);
            }
        }
        try {
            Files.write(marker, OffsetDateTime.now().toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException e) {
            throw alreadyStarted(rowId);
        }
    }

    private static IllegalStateException alreadyStarted(String rowId) {
        return new IllegalStateException("sealed row already scored once (or started by another process): " + rowId);
    }

    /** A marked row may be retried only if its last run failed without persisting any result. */
    static boolean retryAllowed(Path resultDir, String rowId) throws IOException {
        if (Files.exists(results(resultDir, rowId)) || !Files.exists(failed(resultDir, rowId))) {
            return false;
        }
        String record;
        try {
            record = Files.readString(failed(resultDir, rowId));
        } catch (FileSystemException e) {
            return false;   // another starter just claimed the retry
        }
        var failure = JSON.readTree(record);
        return failure.has("results_persisted") && !failure.get("results_persisted").asBoolean();
    }

    static void recordFailure(Path resultDir, String rowId, Exception error, int scoredInMemory) throws IOException {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("failed_at", OffsetDateTime.now().toString());
        failure.put("exception", error.getClass().getName());
        failure.put("queries_scored_in_memory", scoredInMemory);
        failure.put("results_persisted", Files.exists(results(resultDir, rowId)));
        Files.writeString(failed(resultDir, rowId), JSON.writeValueAsString(failure));
    }
}
