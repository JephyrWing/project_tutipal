package com.my.tutipal_back.learning.focus.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.my.tutipal_back.learning.focus.FocusState;

/** PT-90 FocusStore on Postgres (tables from V23__focus_check.sql). */
@Repository
public class JdbcFocusStore implements FocusStore {

    private static final String EXPIRED_SESSIONS = "SELECT id FROM study_sessions WHERE ended_at < ?";

    private final JdbcTemplate jdbc;

    public JdbcFocusStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int insertSamples(long sessionId, long userId, List<Sample> samples) {
        if (samples.isEmpty()) {
            return 0;
        }
        List<Object[]> args = new ArrayList<>(samples.size());
        for (Sample s : samples) {
            args.add(new Object[] {sessionId, userId, time(s.minuteAt()), s.exeName(), s.category(), s.idleSec(),
                    s.switchCount()});
        }
        int[] counts = jdbc.batchUpdate("INSERT INTO focus_samples"
                + " (session_id, user_id, minute_at, exe_name, category, idle_sec, switch_count)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT (session_id, user_id, minute_at) DO NOTHING", args);
        int inserted = 0;
        for (int c : counts) {
            inserted += Math.max(c, 0);
        }
        return inserted;
    }

    @Override
    public List<Sample> recentSamples(long sessionId, long userId, Instant since) {
        return jdbc.query("SELECT minute_at, exe_name, category, idle_sec, switch_count FROM focus_samples"
                        + " WHERE session_id = ? AND user_id = ? AND minute_at >= ? ORDER BY minute_at",
                JdbcFocusStore::sample, sessionId, userId, time(since));
    }

    @Override
    public void upsertState(long sessionId, long userId, FocusState state, String modelVersion, Instant updatedAt) {
        jdbc.update("INSERT INTO focus_states (session_id, user_id, state, model_version, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?)"
                        + " ON CONFLICT (session_id, user_id) DO UPDATE SET state = EXCLUDED.state,"
                        + " model_version = EXCLUDED.model_version, updated_at = EXCLUDED.updated_at",
                sessionId, userId, state.name(), modelVersion, time(updatedAt));
    }

    @Override
    public Optional<StudentState> state(long sessionId, long userId) {
        List<StudentState> rows = jdbc.query("SELECT user_id, state, updated_at FROM focus_states"
                + " WHERE session_id = ? AND user_id = ?", JdbcFocusStore::studentState, sessionId, userId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public List<StudentState> studentStates(long sessionId) {
        return jdbc.query("SELECT p.user_id, s.state, s.updated_at FROM session_participants p"
                        + " LEFT JOIN focus_states s ON s.session_id = p.session_id AND s.user_id = p.user_id"
                        + " WHERE p.session_id = ? AND p.role = ? ORDER BY p.user_id",
                JdbcFocusStore::studentState, sessionId, "STUDENT");
    }

    @Override
    public Optional<Consent> consent(long userId) {
        List<Consent> rows = jdbc.query("SELECT version, consented_at FROM focus_consents WHERE user_id = ?",
                (rs, n) -> new Consent(rs.getString("version"), instant(rs, "consented_at")), userId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public Optional<Consent> lockConsent(long userId) {
        List<Consent> rows = jdbc.query("SELECT version, consented_at FROM focus_consents WHERE user_id = ? FOR SHARE",
                (rs, n) -> new Consent(rs.getString("version"), instant(rs, "consented_at")), userId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public void saveConsent(long userId, String version, Instant consentedAt) {
        jdbc.update("INSERT INTO focus_consents (user_id, version, consented_at) VALUES (?, ?, ?)"
                        + " ON CONFLICT (user_id) DO UPDATE SET version = EXCLUDED.version,"
                        + " consented_at = EXCLUDED.consented_at",
                userId, version, time(consentedAt));
    }

    @Override
    public void deleteUserData(long userId) {
        // consent first: its row lock makes a running write (lockConsent) finish before the rows below are deleted
        jdbc.update("DELETE FROM focus_consents WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM focus_samples WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM focus_states WHERE user_id = ?", userId);
    }

    @Override
    public int deleteExpiredSamples(Instant endedBefore, int limit) {
        return jdbc.update("DELETE FROM focus_samples WHERE id IN (SELECT id FROM focus_samples"
                + " WHERE session_id IN (" + EXPIRED_SESSIONS + ") LIMIT ?)", time(endedBefore), limit);
    }

    @Override
    public int deleteExpiredStates(Instant endedBefore, int limit) {
        return jdbc.update("DELETE FROM focus_states WHERE (session_id, user_id) IN (SELECT session_id, user_id"
                + " FROM focus_states WHERE session_id IN (" + EXPIRED_SESSIONS + ") LIMIT ?)", time(endedBefore), limit);
    }

    @Override
    public int deleteStaleSamples(Instant minuteBefore, int limit) {
        return jdbc.update("DELETE FROM focus_samples WHERE id IN (SELECT id FROM focus_samples"
                + " WHERE minute_at < ? LIMIT ?)", time(minuteBefore), limit);
    }

    @Override
    public int deleteStaleStates(Instant updatedBefore, int limit) {
        return jdbc.update("DELETE FROM focus_states WHERE (session_id, user_id) IN (SELECT session_id, user_id"
                + " FROM focus_states WHERE updated_at < ? LIMIT ?)", time(updatedBefore), limit);
    }

    private static Sample sample(ResultSet rs, int row) throws SQLException {
        return new Sample(instant(rs, "minute_at"), rs.getString("exe_name"), rs.getString("category"),
                rs.getInt("idle_sec"), rs.getInt("switch_count"));
    }

    private static StudentState studentState(ResultSet rs, int row) throws SQLException {
        String state = rs.getString("state");
        return new StudentState(rs.getLong("user_id"), state == null ? null : FocusState.valueOf(state),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime time(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
