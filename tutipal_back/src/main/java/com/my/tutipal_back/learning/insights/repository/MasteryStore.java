package com.my.tutipal_back.learning.insights.repository;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.my.tutipal_back.learning.quiz.entity.QuizType;

/**
 * PT-87: everything the BKT mastery table needs for one session, read with three set-based queries
 * (no per-student or per-concept queries). Nothing is stored: the service recomputes on every request.
 */
@Repository
public class MasteryStore {

    /** quizCount = quizzes of the session tagged with the concept. */
    public record ConceptInfo(long id, String name, int quizCount) {
    }

    /** A current STUDENT participant. */
    public record StudentInfo(long userId, String name) {
    }

    /**
     * One graded-or-not answer of a current student to a tagged quiz, once per (answer, concept).
     * choiceCount = number of choices (0 for TEXT). correct null = not graded yet.
     */
    public record Observation(long userId, long conceptId, QuizType type, int choiceCount, Boolean correct) {
    }

    /** observations are in answer order (created_at, id). */
    public record Snapshot(List<ConceptInfo> concepts, List<StudentInfo> students, List<Observation> observations) {
    }

    private final JdbcTemplate jdbc;

    public MasteryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Snapshot load(long sessionId) {
        List<ConceptInfo> concepts = jdbc.query("""
                SELECT c.id, c.name, count(qc.quiz_id)
                FROM session_concepts c LEFT JOIN quiz_concepts qc ON qc.concept_id = c.id
                WHERE c.session_id = ?
                GROUP BY c.id, c.name
                ORDER BY c.id
                """, (rs, n) -> new ConceptInfo(rs.getLong(1), rs.getString(2), rs.getInt(3)), sessionId);
        List<StudentInfo> students = jdbc.query("""
                SELECT u.id, u.name
                FROM session_participants p JOIN users u ON u.id = p.user_id
                WHERE p.session_id = ? AND p.role = 'STUDENT'
                ORDER BY u.name, u.id
                """, (rs, n) -> new StudentInfo(rs.getLong(1), rs.getString(2)), sessionId);
        List<Observation> observations = jdbc.query("""
                SELECT a.user_id, qc.concept_id, q.type, COALESCE(cardinality(q.choices), 0), a.correct
                FROM quiz_answers a
                JOIN quizzes q ON q.id = a.quiz_id
                JOIN quiz_concepts qc ON qc.quiz_id = q.id
                WHERE q.session_id = ?
                  AND EXISTS (SELECT 1 FROM session_participants p
                              WHERE p.session_id = q.session_id AND p.user_id = a.user_id AND p.role = 'STUDENT')
                ORDER BY a.created_at, a.id, qc.concept_id
                """, (rs, n) -> new Observation(rs.getLong(1), rs.getLong(2), QuizType.valueOf(rs.getString(3)),
                rs.getInt(4), rs.getObject(5, Boolean.class)), sessionId);
        return new Snapshot(concepts, students, observations);
    }
}
