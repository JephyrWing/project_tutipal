package com.my.tutipal_back.materials.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.my.tutipal_back.materials.entity.Material;

public interface MaterialRepository extends JpaRepository<Material, Long> {

    /** 목록용: 파일 내용(data)은 읽지 않는다 */
    record MaterialRow(Long id, Long sessionId, String filename, int sizeBytes, Material.Status status,
                       int chunkCount, String error, Instant createdAt, Instant indexedAt, boolean indexRetryable) {
    }

    String ROW = "select new com.my.tutipal_back.materials.repository.MaterialRepository$MaterialRow("
            + "m.id, m.sessionId, m.filename, m.sizeBytes, m.status, m.chunkCount, m.error, m.createdAt, "
            + "m.indexedAt, m.indexRetryable) from Material m";

    @Query(ROW + " where m.sessionId = :sessionId order by m.id desc")
    List<MaterialRow> rows(@Param("sessionId") Long sessionId);

    @Query(ROW + " where m.id = :id")
    java.util.Optional<MaterialRow> row(@Param("id") Long id);

    record ReusableMaterial(Long id, String filename, String sessionTitle) {
    }

    @Query("select new com.my.tutipal_back.materials.repository.MaterialRepository$ReusableMaterial("
            + "m.id, m.filename, s.title) from Material m, StudySession s "
            + "where m.sessionId = s.id and s.ownerId = :userId and m.sessionId <> :sessionId order by m.id desc")
    List<ReusableMaterial> reusable(@Param("userId") Long userId, @Param("sessionId") Long sessionId);

    @Query(value = """
            SELECT * FROM materials WHERE id = :id AND status = 'PROCESSING'
                AND (index_retry_at IS NULL OR index_retry_at <= statement_timestamp())
            """, nativeQuery = true)
    java.util.Optional<Material> dueIndex(@Param("id") Long id);

    @Modifying
    @Query(value = """
            UPDATE materials
            SET status = 'PROCESSING', error = NULL, index_retryable = FALSE, index_retry_count = 0,
                index_retry_at = NULL, updated_at = statement_timestamp()
            WHERE id = :id AND (status = 'DONE' OR (status = 'FAILED' AND index_retryable))
            """, nativeQuery = true)
    int queueIndex(@Param("id") Long id);
}
