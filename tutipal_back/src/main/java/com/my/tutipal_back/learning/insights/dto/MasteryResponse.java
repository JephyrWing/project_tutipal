package com.my.tutipal_back.learning.insights.dto;

import java.util.List;

/**
 * PT-87 GET /api/sessions/{sid}/insights/mastery: BKT estimate per current student x session concept,
 * recomputed from the stored answers on every request.
 */
public record MasteryResponse(List<ConceptColumn> concepts, List<StudentRow> students) {

    /**
     * @param quizCount  quizzes tagged with this concept
     * @param classAvg   mean of the students' pKnown that are not null (0..100), null when nobody has an observation
     * @param belowHalf  classAvg is below 50
     */
    public record ConceptColumn(Long id, String name, int quizCount, Integer classAvg, boolean belowHalf) {
    }

    /** cells follow the order of {@link MasteryResponse#concepts()}. */
    public record StudentRow(Long userId, String name, List<Cell> cells) {
    }

    /**
     * @param pKnown        BKT P(known) as an integer percent, null with no graded observation
     * @param observations  graded answers used
     * @param lowConfidence fewer than 3 observations
     */
    public record Cell(Long conceptId, Integer pKnown, int observations, boolean lowConfidence) {
    }
}
