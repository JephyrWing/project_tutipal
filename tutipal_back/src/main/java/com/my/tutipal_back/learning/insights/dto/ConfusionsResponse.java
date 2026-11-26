package com.my.tutipal_back.learning.insights.dto;

import java.util.List;

/**
 * PT-87 POST /api/sessions/{sid}/insights/confusions. No question text and no student identifier: topics only carry
 * a label (concept name or "태그되지 않은 주제 N") and counts.
 *
 * @param status   OK | LOW_QUALITY | INSUFFICIENT_DATA
 * @param reason   FEW_QUESTIONS | FEW_STUDENTS | NO_SHARED_TOPIC, or null
 * @param k        number of clusters FastAPI chose (null when no analysis ran)
 * @param cached   true when the answer came from the in-memory result cache (no AI call, no quota)
 */
public record ConfusionsResponse(
        String status,
        String reason,
        int questionCount,
        int studentCount,
        String algorithm,
        Integer k,
        Double silhouette,
        boolean cached,
        List<Topic> topics) {

    public static final String OK = "OK";
    public static final String LOW_QUALITY = "LOW_QUALITY";
    public static final String INSUFFICIENT_DATA = "INSUFFICIENT_DATA";
    public static final String FEW_QUESTIONS = "FEW_QUESTIONS";
    public static final String FEW_STUDENTS = "FEW_STUDENTS";
    public static final String NO_SHARED_TOPIC = "NO_SHARED_TOPIC";

    /** Same result marked as served from the cache. */
    public ConfusionsResponse asCached() {
        return new ConfusionsResponse(status, reason, questionCount, studentCount, algorithm, k, silhouette, true,
                topics);
    }

    /** One of the TOP3 topics. conceptId is null for an untagged topic. */
    public record Topic(int rank, String label, Long conceptId, int questionCount, int studentCount) {
    }
}
