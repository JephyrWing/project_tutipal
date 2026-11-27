package com.my.tutipal_back.materials.service;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.my.tutipal_back.common.exception.ApiException;

@Service
class MaterialReconciliation {
    private static final Logger log = LoggerFactory.getLogger(MaterialReconciliation.class);
    private static final String MISSING_MESSAGE = "원본 파일을 찾지 못했어요. 운영자에게 문의해 주세요";

    record CleanupCandidate(long id, long sessionId, String key, String reason, String lastError) {}
    record Original(long id, String key, boolean missing, String status) {}
    record OriginalCandidate(long id, Presence presence, boolean hidden) {}

    enum Presence { PRESENT, MISSING, FORBIDDEN, TEMPORARY, UNKNOWN }
    enum Result { DONE, NO_CHANGE, BUSY, REFERENCED, MISSING, PRESENT, FORBIDDEN, TEMPORARY, UNKNOWN, RETRY }

    private final JdbcTemplate jdbc;
    private final MaterialObjectStore objects;
    private final TransactionTemplate independent;

    MaterialReconciliation(JdbcTemplate jdbc, MaterialObjectStore objects, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.objects = objects;
        this.independent = new TransactionTemplate(transactionManager);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.independent.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    void reserveUpload(long sessionId, String key) {
        // Hold the object lock on the upload transaction until its material row has committed or rolled back.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext('material-object'), hashtext(?))", rs -> {}, key);
        independent.executeWithoutResult(status -> record(sessionId, key, "UPLOAD"));
    }

    void finishUpload(String key) {
        jdbc.update("DELETE FROM material_object_cleanup WHERE storage_scope = ? AND storage_key = ?",
                objects.scope(), key);
    }

    void recordDeletion(long sessionId, String key) {
        record(sessionId, key, "DELETE");
    }

    private void record(long sessionId, String key, String reason) {
        jdbc.update("""
                INSERT INTO material_object_cleanup (storage_scope, storage_key, session_id, reason)
                VALUES (?, ?, ?, ?) ON CONFLICT (storage_scope, storage_key) DO NOTHING
                """, objects.scope(), key, sessionId, reason);
    }

    void cleanupAfterTransaction(String key) {
        try {
            independent.execute(status -> clean(key, null));
        } catch (RuntimeException ex) {
            // The durable record remains even if the database is unavailable after the S3 call.
            log.warn("material cleanup postponed: key={} error={}", key, ex.getClass().getSimpleName());
        }
    }

    List<CleanupCandidate> cleanupCandidates(long sessionId) {
        return jdbc.query("""
                SELECT id, session_id, storage_key, reason, last_error FROM material_object_cleanup
                WHERE storage_scope = ? AND session_id = ? ORDER BY id
                """, (rs, n) -> new CleanupCandidate(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)), objects.scope(), sessionId);
    }

    List<OriginalCandidate> originalCandidates(long sessionId) {
        List<Original> originals = jdbc.query("""
                SELECT id, storage_key, original_missing, status FROM materials WHERE session_id = ? ORDER BY id
                """, (rs, n) -> new Original(rs.getLong(1), rs.getString(2), rs.getBoolean(3), rs.getString(4)),
                sessionId);
        return originals.stream().map(original -> {
            Presence presence = presence(original.key());
            return presence == Presence.PRESENT && !original.missing() ? null
                    : new OriginalCandidate(original.id(), presence, original.missing());
        }).filter(Objects::nonNull).toList();
    }

    Result cleanup(long candidateId) {
        return independent.execute(status -> {
            List<String> keys = jdbc.queryForList("""
                    SELECT storage_key FROM material_object_cleanup WHERE storage_scope = ? AND id = ?
                    """, String.class, objects.scope(), candidateId);
            return keys.isEmpty() ? Result.NO_CHANGE : clean(keys.getFirst(), candidateId);
        });
    }

    private Result clean(String key, Long candidateId) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(hashtext('material-object'), hashtext(?))", Boolean.class, key))) {
            return Result.BUSY;
        }
        List<Long> current = jdbc.queryForList("""
                SELECT id FROM material_object_cleanup WHERE storage_scope = ? AND storage_key = ?
                """, Long.class, objects.scope(), key);
        if (current.isEmpty() || (candidateId != null && !candidateId.equals(current.getFirst()))) {
            return Result.NO_CHANGE;
        }
        boolean verify = candidateId != null;
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM materials WHERE storage_key = ?)", Boolean.class, key))) {
            return Result.REFERENCED;
        }
        try {
            if (!verify || objects.exists(key)) {
                objects.delete(key);
                if (verify && objects.exists(key)) {
                    return cleanupFailure(key, "OBJECT_STILL_PRESENT", Result.RETRY);
                }
            }
        } catch (MaterialObjectStore.ReadFailure failure) {
            return cleanupFailure(key, failure.kind().name(), Result.valueOf(failure.kind().name()));
        } catch (ApiException failure) {
            return cleanupFailure(key, "DELETE_FAILED", Result.RETRY);
        }
        finishUpload(key);
        return Result.DONE;
    }

    private Result cleanupFailure(String key, String error, Result result) {
        jdbc.update("""
                UPDATE material_object_cleanup SET last_error = ? WHERE storage_scope = ? AND storage_key = ?
                """, error, objects.scope(), key);
        return result;
    }

    Result markMissing(long materialId) {
        return changeOriginal(materialId, false);
    }

    Result restore(long materialId) {
        return changeOriginal(materialId, true);
    }

    private Result changeOriginal(long id, boolean restore) {
        return independent.execute(status -> {
            // The existing index worker uses the same negative material ID.
            if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, -id))) {
                return Result.BUSY;
            }
            List<Original> rows = jdbc.query("""
                    SELECT id, storage_key, original_missing, status FROM materials WHERE id = ? FOR UPDATE
                    """, (rs, n) -> new Original(rs.getLong(1), rs.getString(2), rs.getBoolean(3), rs.getString(4)), id);
            if (rows.isEmpty()) {
                return Result.NO_CHANGE;
            }
            Original material = rows.getFirst();
            Presence presence = presence(material.key());
            if (restore) {
                if (presence != Presence.PRESENT) {
                    return Result.valueOf(presence.name());
                }
                if (!material.missing() || material.status().equals("PROCESSING")) {
                    return Result.NO_CHANGE;
                }
                jdbc.update("""
                        UPDATE materials SET status = 'PROCESSING', error = NULL, index_retryable = FALSE,
                            index_retry_count = 0, index_retry_at = NULL, updated_at = statement_timestamp()
                        WHERE id = ?
                        """, id);
            } else {
                if (presence != Presence.MISSING) {
                    return Result.valueOf(presence.name());
                }
                if (material.missing() && !material.status().equals("PROCESSING")) {
                    return Result.NO_CHANGE;
                }
                jdbc.update("""
                        UPDATE materials SET original_missing = TRUE, status = 'FAILED', error = ?,
                            index_retryable = FALSE, index_retry_at = NULL, updated_at = statement_timestamp()
                        WHERE id = ?
                        """, MISSING_MESSAGE, id);
            }
            return Result.DONE;
        });
    }

    private Presence presence(String key) {
        try {
            return objects.exists(key) ? Presence.PRESENT : Presence.MISSING;
        } catch (MaterialObjectStore.ReadFailure failure) {
            return Presence.valueOf(failure.kind().name());
        }
    }
}
