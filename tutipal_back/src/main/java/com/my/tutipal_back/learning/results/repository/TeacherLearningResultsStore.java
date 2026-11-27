package com.my.tutipal_back.learning.results.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.my.tutipal_back.learning.results.dto.TeacherLearningResults;
import com.my.tutipal_back.learning.results.dto.TeacherLearningResults.Counts;
import com.my.tutipal_back.learning.results.dto.TeacherLearningResults.Question;
import com.my.tutipal_back.learning.results.dto.TeacherLearningResults.Student;

@Repository
public class TeacherLearningResultsStore {

    private static final String RESULTS = """
        WITH room_questions AS (
            SELECT id, question, closes_at FROM quizzes WHERE session_id = ?
        ), current_students AS (
            SELECT user_id, created_at FROM session_participants
            WHERE session_id = ? AND role = 'STUDENT'
        ), submissions AS (
            SELECT a.quiz_id, a.user_id,
                   CASE WHEN a.graded_by IS NULL OR a.correct IS NULL THEN 'PENDING'
                        WHEN a.correct THEN 'CORRECT' ELSE 'INCORRECT' END AS verdict
            FROM quiz_answers a JOIN room_questions q ON q.id = a.quiz_id
        ), outcomes AS (
            SELECT quiz_id, user_id, verdict FROM submissions
            UNION ALL
            SELECT q.id, p.user_id, 'UNSUBMITTED'
            FROM room_questions q JOIN current_students p ON p.created_at < q.closes_at
            WHERE NOT EXISTS (
                SELECT 1 FROM submissions a WHERE a.quiz_id = q.id AND a.user_id = p.user_id
            )
        ), counts AS (
            SELECT quiz_id, user_id,
                   count(*) FILTER (WHERE verdict = 'UNSUBMITTED') AS unsubmitted,
                   count(*) FILTER (WHERE verdict = 'PENDING') AS pending,
                   count(*) FILTER (WHERE verdict = 'CORRECT') AS correct,
                   count(*) FILTER (WHERE verdict = 'INCORRECT') AS incorrect
            FROM outcomes GROUP BY GROUPING SETS ((), (user_id), (quiz_id))
        ), student_ids AS (
            SELECT user_id FROM current_students UNION SELECT user_id FROM submissions
        )
        SELECT 'TOTAL' AS row_type, NULL::bigint AS id, NULL::text AS title, NULL::timestamptz AS closes_at,
               unsubmitted, pending, correct, incorrect
        FROM counts WHERE quiz_id IS NULL AND user_id IS NULL
        UNION ALL
        SELECT 'STUDENT', u.id, u.name, NULL::timestamptz,
               c.unsubmitted, c.pending, c.correct, c.incorrect
        FROM student_ids s JOIN users u ON u.id = s.user_id LEFT JOIN counts c ON c.user_id = s.user_id
        UNION ALL
        SELECT 'QUESTION', q.id, q.question, q.closes_at, c.unsubmitted, c.pending, c.correct, c.incorrect
        FROM room_questions q LEFT JOIN counts c ON c.quiz_id = q.id
        ORDER BY row_type, id
        """;

    private final JdbcTemplate jdbc;

    public TeacherLearningResultsStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public TeacherLearningResults results(long sessionId) {
        return jdbc.query(RESULTS, TeacherLearningResultsStore::readResults, sessionId, sessionId);
    }

    private static TeacherLearningResults readResults(ResultSet rows) throws SQLException {
        Counts totals = new Counts(0, 0, 0, 0);
        List<Student> students = new ArrayList<>();
        List<Question> questions = new ArrayList<>();
        while (rows.next()) {
            Counts counts = new Counts(rows.getLong("unsubmitted"), rows.getLong("pending"),
                    rows.getLong("correct"), rows.getLong("incorrect"));
            switch (rows.getString("row_type")) {
                case "TOTAL" -> totals = counts;
                case "STUDENT" -> students.add(new Student(rows.getLong("id"), rows.getString("title"), counts));
                case "QUESTION" -> questions.add(new Question(rows.getLong("id"), rows.getString("title"),
                        rows.getTimestamp("closes_at").toInstant(), counts));
                default -> throw new IllegalStateException("Unexpected teacher results row");
            }
        }
        return new TeacherLearningResults(totals, List.copyOf(students), List.copyOf(questions));
    }
}
