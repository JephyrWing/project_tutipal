package com.my.tutipal_back.learning.insights.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.my.tutipal_back.learning.insights.dto.ConceptView;
import com.my.tutipal_back.learning.insights.dto.QuizConceptView;

/** PT-87: session_concepts and quiz_concepts. Callers own the transaction. */
@Repository
public class ConceptStore {

    private final JdbcTemplate jdbc;

    public ConceptStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Display form: Unicode whitespace runs (incl. NBSP and U+3000, like JS \s) become one space, ends trimmed. */
    public static String clean(String name) {
        return name.replaceAll("(?U)\\s+", " ").strip();
    }

    /** Same concept when equal after {@link #clean} and lower-casing ("For  Loop" = "for loop"). */
    public static String normalize(String name) {
        return clean(name).toLowerCase(Locale.ROOT);
    }

    /**
     * Serializes tag changes of one session (the 50-concept limit check and the orphan cleanup).
     * NO KEY UPDATE so that inserts referencing the session (chat questions, answers) are not blocked.
     */
    public void lockSession(long sessionId) {
        jdbc.query("SELECT id FROM study_sessions WHERE id = ? FOR NO KEY UPDATE", (rs, n) -> rs.getLong(1),
                sessionId);
    }

    /**
     * Concepts of the session that no quiz other than quizId uses. Replacing quizId's tags without them makes them
     * orphans, which {@link #replaceQuizConcepts} removes, so they must not count against the 50-concept limit.
     */
    public List<ConceptView> releasableConcepts(long sessionId, long quizId) {
        return jdbc.query("""
                SELECT c.id, c.name FROM session_concepts c
                WHERE c.session_id = ?
                  AND NOT EXISTS (SELECT 1 FROM quiz_concepts qc WHERE qc.concept_id = c.id AND qc.quiz_id <> ?)
                ORDER BY c.id
                """, (rs, n) -> new ConceptView(rs.getLong(1), rs.getString(2)), sessionId, quizId);
    }

    public List<ConceptView> sessionConcepts(long sessionId) {
        return jdbc.query("SELECT id, name FROM session_concepts WHERE session_id = ? ORDER BY id",
                (rs, n) -> new ConceptView(rs.getLong(1), rs.getString(2)), sessionId);
    }

    /** quizId -> its tags, for every tagged quiz of the session. */
    public Map<Long, List<QuizConceptView>> quizConcepts(long sessionId) {
        Map<Long, List<QuizConceptView>> byQuiz = new LinkedHashMap<>();
        jdbc.query("""
                SELECT qc.quiz_id, c.id, c.name, qc.source
                FROM quiz_concepts qc JOIN session_concepts c ON c.id = qc.concept_id
                WHERE c.session_id = ?
                ORDER BY qc.quiz_id, c.id
                """, rs -> {
            byQuiz.computeIfAbsent(rs.getLong(1), id -> new ArrayList<>())
                    .add(new QuizConceptView(rs.getLong(2), rs.getString(3), rs.getString(4)));
        }, sessionId);
        return byQuiz;
    }

    /**
     * Replaces the whole tag set of the quiz. A name whose normalized form already exists in the session reuses that
     * concept (its first spelling is kept); new names become new session concepts. Concepts left without any quiz
     * are removed so that abandoned names do not use up the 50-per-session limit. Names must already be validated
     * and de-duplicated by the caller.
     *
     * @return the quiz's tags in the order of names
     */
    public List<QuizConceptView> replaceQuizConcepts(long sessionId, long quizId, List<String> names, String source) {
        List<QuizConceptView> tags = new ArrayList<>();
        for (String name : names) {
            String normalized = normalize(name);
            jdbc.update("""
                    INSERT INTO session_concepts (session_id, name, normalized_name, created_at)
                    VALUES (?, ?, ?, now())
                    ON CONFLICT (session_id, normalized_name) DO NOTHING
                    """, sessionId, name, normalized);
            ConceptView concept = jdbc.queryForObject(
                    "SELECT id, name FROM session_concepts WHERE session_id = ? AND normalized_name = ?",
                    (rs, n) -> new ConceptView(rs.getLong(1), rs.getString(2)), sessionId, normalized);
            tags.add(new QuizConceptView(concept.id(), concept.name(), source));
        }
        jdbc.update("DELETE FROM quiz_concepts WHERE quiz_id = ?", quizId);
        for (QuizConceptView tag : tags) {
            jdbc.update("INSERT INTO quiz_concepts (quiz_id, concept_id, source, created_at) VALUES (?, ?, ?, now())",
                    quizId, tag.conceptId(), source);
        }
        jdbc.update("""
                DELETE FROM session_concepts c
                WHERE c.session_id = ? AND NOT EXISTS (SELECT 1 FROM quiz_concepts qc WHERE qc.concept_id = c.id)
                """, sessionId);
        return tags;
    }
}
