package com.my.tutipal_back.learning.insights.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * PT-87: chat_questions. This is the only place in the code base that reads question_text, and it hands the text
 * out wrapped in {@link RawQuestion}. Every method is a single statement: it joins the caller's transaction when
 * there is one (leave / kick listener) and otherwise runs in autocommit (recorder, retention job, analysis read).
 *
 * <p>The analysis reads through the service-side {@code ChatQuestionSource} adapter, so this package does not depend
 * on the service package (QuestionRow / RawQuestion live here, next to the only reader of the text).
 */
@Repository
public class ChatQuestionStore {

    private final JdbcTemplate jdbc;

    public ChatQuestionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Stores the question only when the user is a STUDENT participant of a session that is still open
     * (checked in the same statement, so a student who left or a closed session gets 0 rows).
     *
     * @return rows inserted (0 or 1)
     */
    public int insertIfActiveStudent(long sessionId, long userId, String questionText) {
        return jdbc.update("""
                INSERT INTO chat_questions (session_id, user_id, question_text, created_at)
                SELECT CAST(? AS BIGINT), CAST(? AS BIGINT), CAST(? AS TEXT), now()
                WHERE EXISTS (
                    SELECT 1 FROM session_participants p
                    JOIN study_sessions s ON s.id = p.session_id
                    WHERE p.session_id = ? AND p.user_id = ? AND p.role = 'STUDENT' AND s.ended_at IS NULL)
                """, sessionId, userId, questionText, sessionId, userId);
    }

    /**
     * Newest first, at most limit rows, only from users who are STUDENT participants of the session right now
     * (a row left behind by a leave racing with a chat insert is never analyzed).
     */
    public List<QuestionRow> recentQuestions(long sessionId, int limit) {
        return recentQuestions(sessionId, limit, Instant.EPOCH);
    }

    /**
     * Same, but only rows created at or after since. The analysis passes now - retention so rows that expired
     * between two retention-job runs are never analyzed (ChatQuestionSource).
     */
    public List<QuestionRow> recentQuestions(long sessionId, int limit, Instant since) {
        return jdbc.query("""
                        SELECT q.id, q.user_id, q.question_text FROM chat_questions q
                        WHERE q.session_id = ? AND q.created_at >= ?
                          AND EXISTS (SELECT 1 FROM session_participants p
                                      WHERE p.session_id = q.session_id AND p.user_id = q.user_id
                                        AND p.role = 'STUDENT')
                        ORDER BY q.id DESC LIMIT ?
                        """,
                (rs, n) -> new QuestionRow(rs.getLong(1), rs.getLong(2), new RawQuestion(rs.getString(3))),
                sessionId, Timestamp.from(since), limit);
    }

    /** Deletes at most limit rows created before cutoff (oldest first). Returns how many were deleted. */
    public int deleteCreatedBefore(Instant cutoff, int limit) {
        return jdbc.update("""
                DELETE FROM chat_questions WHERE id IN (
                    SELECT id FROM chat_questions WHERE created_at < ? ORDER BY id LIMIT ?)
                """, Timestamp.from(cutoff), limit);
    }

    /** A student left or was removed: that student's questions in that session only. */
    public int deleteBySessionAndUser(long sessionId, long userId) {
        return jdbc.update("DELETE FROM chat_questions WHERE session_id = ? AND user_id = ?", sessionId, userId);
    }
}
