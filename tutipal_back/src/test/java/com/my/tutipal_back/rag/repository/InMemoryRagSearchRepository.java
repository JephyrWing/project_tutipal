package com.my.tutipal_back.rag.repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.my.tutipal_back.rag.dto.RagHit;

/**
 * PT-86 eval stand-in for {@link RagSearchRepository} with the same semantics as findNearest:
 * session filter, embedding_model filter, indexed material only, ORDER BY cosine distance ASC, LIMIT.
 * The SQL itself is checked against real pgvector by RagSearchRepositoryPostgresTest (ragDbTest, CI).
 */
public class InMemoryRagSearchRepository extends RagSearchRepository {

    /** One stored chunk row (material_chunks JOIN materials). */
    public record Row(long sessionId, boolean indexed, String filename, Integer page, String content,
                      String embeddingModel, float[] embedding) {
    }

    private final List<Row> rows = new ArrayList<>();

    public InMemoryRagSearchRepository() {
        super(null);
    }

    public void add(Row row) {
        rows.add(row);
    }

    public int size() {
        return rows.size();
    }

    @Override
    public boolean hasChunks(Long sessionId) {
        return rows.stream().anyMatch(r -> r.sessionId() == sessionId && r.indexed());
    }

    @Override
    public List<RagHit> findNearest(Long sessionId, String embeddingModel, String vector, int limit) {
        float[] query = parse(vector);
        record Scored(Row row, double similarity) {
        }
        return rows.stream()
                .filter(r -> r.sessionId() == sessionId && r.indexed() && r.embeddingModel().equals(embeddingModel))
                .map(r -> new Scored(r, cosine(r.embedding(), query)))
                .sorted(Comparator.comparingDouble((Scored s) -> 1 - s.similarity()))   // stable: insertion order on ties
                .limit(Math.max(0, limit))
                .map(s -> new RagHit(s.row().filename(), s.row().page(), s.row().content(), s.similarity()))
                .toList();
    }

    public static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** Inverse of AiClient.pgVector ("[0.1,0.2,…]"). */
    static float[] parse(String vector) {
        String body = vector.substring(1, vector.length() - 1);
        String[] parts = body.split(",");
        float[] v = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            v[i] = Float.parseFloat(parts[i]);
        }
        return v;
    }
}
