package com.my.tutipal_back.learning.results.repository;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.my.tutipal_back.learning.results.dto.LearningResultsPage.Item;
import com.my.tutipal_back.learning.results.dto.LearningResultsPage.SessionOption;

@Repository
public class LearningResultsStore {
    // The same authorization predicate protects rows, counts and filter options.
    private static final String FROM = """
        FROM quiz_answers a JOIN quizzes q ON q.id=a.quiz_id
        JOIN study_sessions s ON s.id=q.session_id
        JOIN session_participants p ON p.session_id=s.id AND p.user_id=a.user_id
        WHERE a.user_id=? AND p.role='STUDENT' AND s.ended_at IS NULL
        """;
    private static final String VERDICT = "CASE WHEN a.graded_by IS NULL OR a.correct IS NULL THEN 'PENDING' "
            + "WHEN a.correct THEN 'CORRECT' ELSE 'INCORRECT' END";
    private final JdbcTemplate jdbc;
    public LearningResultsStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<SessionOption> sessions(long userId) {
        return jdbc.query("SELECT DISTINCT s.id,s.title " + FROM + " ORDER BY s.title,s.id",
                (rs, n) -> new SessionOption(rs.getLong(1), rs.getString(2)), userId);
    }

    private String filter(Long sessionId, String verdict, List<Object> args) {
        String sql = "";
        if (sessionId != null) { sql += " AND s.id=?"; args.add(sessionId); }
        if (!"ALL".equals(verdict)) { sql += " AND (" + VERDICT + ")=?"; args.add(verdict); }
        return sql;
    }
    public long count(long userId, Long sessionId, String verdict) {
        List<Object> args = new ArrayList<>(List.of(userId));
        String where = filter(sessionId, verdict, args);
        return jdbc.queryForObject("SELECT count(*) " + FROM + where, Long.class, args.toArray());
    }
    public List<Item> list(long userId, Long sessionId, String verdict, int size, long offset) {
        List<Object> args = new ArrayList<>(List.of(userId));
        String where = filter(sessionId, verdict, args);
        args.add(size); args.add(offset);
        return jdbc.query("""
            SELECT a.id,q.id,s.id,s.title,s.topic,q.type,q.question,q.choices,
                   a.choice_index,a.text,a.created_at,
            """ + VERDICT + " " + FROM + where + " ORDER BY a.created_at DESC,a.id DESC LIMIT ? OFFSET ?",
            (rs, n) -> new Item(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getString(4),rs.getString(5),
                rs.getString(6),rs.getString(7),choices(rs.getArray(8)),rs.getObject(9,Integer.class),
                rs.getString(10),rs.getTimestamp(11).toInstant(),rs.getString(12)), args.toArray());
    }
    private static List<String> choices(Array array) throws SQLException {
        if (array == null) return List.of();
        try { return Arrays.asList((String[]) array.getArray()); }
        finally { array.free(); }
    }
}
