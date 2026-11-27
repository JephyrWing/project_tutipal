package com.my.tutipal_back.rag.repository;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.my.tutipal_back.rag.dto.RagHit;

@Repository
public class RagSearchRepository {

    private final JdbcTemplate jdbc;

    public RagSearchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasChunks(Long sessionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT EXISTS (SELECT 1 FROM materials
                    WHERE session_id = ? AND indexed_at IS NOT NULL AND NOT original_missing AND hidden_at IS NULL)
                """,
                Boolean.class, sessionId));
    }

    public List<RagHit> findNearest(Long sessionId, String embeddingModel, String vector, int limit) {
        return jdbc.query("""
                SELECT m.filename, c.page, c.content, 1 - (c.embedding <=> ?::vector) AS sim
                FROM material_chunks c JOIN materials m ON m.id = c.material_id
                WHERE m.session_id = ? AND c.session_id = m.session_id
                    AND m.indexed_at IS NOT NULL AND NOT m.original_missing AND m.hidden_at IS NULL
                    AND c.embedding_model = ?
                ORDER BY c.embedding <=> ?::vector LIMIT ?""",
                (rs, i) -> new RagHit(rs.getString(1), (Integer) rs.getObject(2), rs.getString(3), rs.getDouble(4)),
                vector, sessionId, embeddingModel, vector, limit);
    }
}
