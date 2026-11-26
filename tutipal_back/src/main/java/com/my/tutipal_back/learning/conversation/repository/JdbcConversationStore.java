package com.my.tutipal_back.learning.conversation.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.my.tutipal_back.sessions.entity.StudySession;

/**
 * PT-106 ConversationStore on PostgreSQL. The caller owns the transaction; writes lock the conversation row first.
 *
 * <p>Soft delete: every read filters {@code deleted_at IS NULL} on the conversation, and reads of messages,
 * summaries and memories also require their conversation to be live ({@link #LIVE_PARENT}), so a deleted
 * conversation's rows can never reach a response or an Agent request even before they are purged.
 */
@Repository
public class JdbcConversationStore implements ConversationStore {

    /** Correlated check on a child row's conversation_id (messages, summaries, memories). */
    static final String LIVE_PARENT = " EXISTS (SELECT 1 FROM ai_conversations live WHERE live.id = %s.conversation_id"
            + " AND live.deleted_at IS NULL)";

    private static final String CONVERSATION_COLUMNS =
            "id, user_id, session_id, topic, title, message_count, created_at, last_message_at, saved_at";
    private static final String MESSAGE_COLUMNS = "id, conversation_id, role, content, created_at";
    private static final String SUMMARY_COLUMNS =
            "id, conversation_id, text, from_message_id, to_message_id, created_at, superseded_at, invalidated_at";
    private static final String MEMORY_COLUMNS = "id, user_id, session_id, conversation_id, text, created_at";
    private static final String LIVE_MESSAGE = String.format(LIVE_PARENT, "ai_conversation_messages");
    private static final String LIVE_SUMMARY = String.format(LIVE_PARENT, "ai_conversation_summaries");
    /** A live memory of a live conversation (recall, detail and the 20 cap). */
    private static final String LIVE_MEMORY = " ai_memories.deleted_at IS NULL AND"
            + String.format(LIVE_PARENT, "ai_memories");

    /**
     * A class conversation's session ended more than its retention ago. A session without ai_retention_days (rows
     * inserted outside the API; V24 backfilled existing ones) counts as the maximum. {@code ended_at < ?} is implied
     * by the second condition (days >= 0) and lets the ended_at index narrow the scan.
     */
    static final String SESSION_EXPIRED = " s.ended_at IS NOT NULL AND s.ended_at < ? AND s.ended_at"
            + " + COALESCE(s.ai_retention_days, " + StudySession.MAX_AI_RETENTION_DAYS + ") * INTERVAL '1 day' < ?";

    private final JdbcTemplate jdbc;

    public JdbcConversationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Conversation insertConversation(long userId, Long sessionId, String topic, Instant now) {
        return jdbc.queryForObject("INSERT INTO ai_conversations (user_id, session_id, topic, created_at, last_message_at)"
                        + " VALUES (?, ?, ?, ?, ?) RETURNING " + CONVERSATION_COLUMNS,
                JdbcConversationStore::conversation, userId, sessionId, topic, time(now), time(now));
    }

    @Override
    public Optional<Conversation> findOwned(long userId, long conversationId) {
        return first(jdbc.query("SELECT " + CONVERSATION_COLUMNS + " FROM ai_conversations WHERE id = ? AND user_id = ?"
                        + " AND deleted_at IS NULL", JdbcConversationStore::conversation, conversationId, userId));
    }

    @Override
    public Optional<Conversation> lockOwned(long userId, long conversationId) {
        return first(jdbc.query("SELECT " + CONVERSATION_COLUMNS
                        + " FROM ai_conversations WHERE id = ? AND user_id = ? AND deleted_at IS NULL FOR UPDATE",
                JdbcConversationStore::conversation, conversationId, userId));
    }

    @Override
    public Optional<Conversation> find(long conversationId) {
        return first(jdbc.query("SELECT " + CONVERSATION_COLUMNS
                        + " FROM ai_conversations WHERE id = ? AND deleted_at IS NULL",
                JdbcConversationStore::conversation, conversationId));
    }

    /** Transaction-scoped advisory lock on (user, scope); released at commit / rollback. */
    @Override
    public void lockMemoryScope(long userId, Long sessionId) {
        String key = "ai_memories:" + userId + ":" + (sessionId == null ? "personal" : sessionId);
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", (rs, row) -> null, key);
    }

    @Override
    public Optional<Conversation> lock(long conversationId) {
        return first(jdbc.query("SELECT " + CONVERSATION_COLUMNS
                        + " FROM ai_conversations WHERE id = ? AND deleted_at IS NULL FOR UPDATE",
                JdbcConversationStore::conversation, conversationId));
    }

    @Override
    public List<Conversation> list(long userId, Long sessionId, Cursor after, int limit) {
        String scope = sessionId == null ? "session_id IS NULL" : "session_id = ?";
        String keyset = after == null ? "" : " AND (last_message_at, id) < (?, ?)";
        String sql = "SELECT " + CONVERSATION_COLUMNS + " FROM ai_conversations WHERE user_id = ? AND " + scope
                + " AND deleted_at IS NULL" + keyset
                + " ORDER BY last_message_at DESC, id DESC LIMIT ?";
        ArrayList<Object> args = new ArrayList<>();
        args.add(userId);
        if (sessionId != null) {
            args.add(sessionId);
        }
        if (after != null) {
            args.add(time(after.lastMessageAt()));
            args.add(after.id());
        }
        args.add(limit);
        return jdbc.query(sql, JdbcConversationStore::conversation, args.toArray());
    }

    @Override
    public void softDeleteConversation(long conversationId, Instant now) {
        jdbc.update("UPDATE ai_conversations SET deleted_at = ? WHERE id = ? AND deleted_at IS NULL", time(now),
                conversationId);
    }

    @Override
    public List<Message> messagesBefore(long conversationId, Long beforeId, int limit) {
        if (beforeId == null) {
            return jdbc.query("SELECT " + MESSAGE_COLUMNS + " FROM ai_conversation_messages WHERE conversation_id = ?"
                    + " AND" + LIVE_MESSAGE + " ORDER BY id DESC LIMIT ?", JdbcConversationStore::message,
                    conversationId, limit);
        }
        return jdbc.query("SELECT " + MESSAGE_COLUMNS + " FROM ai_conversation_messages WHERE conversation_id = ?"
                + " AND id < ? AND" + LIVE_MESSAGE + " ORDER BY id DESC LIMIT ?", JdbcConversationStore::message,
                conversationId, beforeId, limit);
    }

    @Override
    public List<Message> recentMessagesAfter(long conversationId, long afterMessageId, int limit) {
        return jdbc.query("SELECT " + MESSAGE_COLUMNS + " FROM (SELECT " + MESSAGE_COLUMNS
                        + " FROM ai_conversation_messages WHERE conversation_id = ? AND id > ? AND" + LIVE_MESSAGE
                        + " ORDER BY id DESC LIMIT ?)"
                        + " recent ORDER BY id",
                JdbcConversationStore::message, conversationId, afterMessageId, limit);
    }

    @Override
    public int countMessagesAfter(long conversationId, long afterMessageId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM ai_conversation_messages WHERE conversation_id = ? AND id > ? AND" + LIVE_MESSAGE,
                Integer.class, conversationId, afterMessageId);
        return count == null ? 0 : count;
    }

    @Override
    public List<Message> oldestMessagesAfter(long conversationId, long afterMessageId, int limit) {
        return jdbc.query("SELECT " + MESSAGE_COLUMNS + " FROM ai_conversation_messages WHERE conversation_id = ?"
                + " AND id > ? AND" + LIVE_MESSAGE + " ORDER BY id LIMIT ?", JdbcConversationStore::message,
                conversationId, afterMessageId, limit);
    }

    @Override
    public SavedPair insertPair(long conversationId, String question, String answer, String title, Instant now,
                                UUID clientMessageId) {
        Message user = insertMessage(conversationId, "user", question, now, clientMessageId);
        Message assistant = insertMessage(conversationId, "assistant", answer, now, null);
        jdbc.update("UPDATE ai_conversations SET message_count = message_count + 2, last_message_at = ?,"
                + " title = COALESCE(title, ?) WHERE id = ?", time(now), title, conversationId);
        return new SavedPair(user, assistant);
    }

    private Message insertMessage(long conversationId, String role, String content, Instant now,
                                  UUID clientMessageId) {
        return jdbc.queryForObject("INSERT INTO ai_conversation_messages"
                        + " (conversation_id, role, content, created_at, client_message_id)"
                        + " VALUES (?, ?, ?, ?, ?) RETURNING " + MESSAGE_COLUMNS,
                JdbcConversationStore::message, conversationId, role, content, time(now), clientMessageId);
    }

    /**
     * The keyed question and the next message of the conversation. A pair is inserted under the conversation row
     * lock, so the next message is its answer.
     */
    @Override
    public Optional<SavedPair> findPairByClientMessageId(long conversationId, UUID clientMessageId) {
        List<Message> rows = jdbc.query("SELECT " + MESSAGE_COLUMNS + " FROM ai_conversation_messages"
                        + " WHERE conversation_id = ? AND id >= (SELECT id FROM ai_conversation_messages"
                        + " WHERE conversation_id = ? AND client_message_id = ? AND role = 'user')"
                        + " AND" + LIVE_MESSAGE + " ORDER BY id LIMIT 2",
                JdbcConversationStore::message, conversationId, conversationId, clientMessageId);
        if (rows.size() < 2 || !"user".equals(rows.get(0).role()) || !"assistant".equals(rows.get(1).role())) {
            return Optional.empty();
        }
        return Optional.of(new SavedPair(rows.get(0), rows.get(1)));
    }

    @Override
    public Optional<Summary> activeSummary(long conversationId) {
        return first(jdbc.query("SELECT " + SUMMARY_COLUMNS + " FROM ai_conversation_summaries"
                        + " WHERE conversation_id = ? AND superseded_at IS NULL AND invalidated_at IS NULL AND"
                        + LIVE_SUMMARY,
                JdbcConversationStore::summary, conversationId));
    }

    @Override
    public Optional<Summary> findSummary(long summaryId) {
        return first(jdbc.query("SELECT " + SUMMARY_COLUMNS + " FROM ai_conversation_summaries WHERE id = ? AND"
                        + LIVE_SUMMARY,
                JdbcConversationStore::summary, summaryId));
    }

    @Override
    public void supersedeSummary(long summaryId, Instant now) {
        jdbc.update("UPDATE ai_conversation_summaries SET superseded_at = ? WHERE id = ? AND superseded_at IS NULL",
                time(now), summaryId);
    }

    @Override
    public Summary insertSummary(long conversationId, String text, long fromMessageId, long toMessageId, Instant now) {
        return jdbc.queryForObject("INSERT INTO ai_conversation_summaries"
                        + " (conversation_id, text, from_message_id, to_message_id, created_at)"
                        + " VALUES (?, ?, ?, ?, ?) RETURNING " + SUMMARY_COLUMNS,
                JdbcConversationStore::summary, conversationId, text, fromMessageId, toMessageId, time(now));
    }

    @Override
    public List<Memory> memories(long userId, Long sessionId, int limit) {
        if (sessionId == null) {
            return jdbc.query("SELECT " + MEMORY_COLUMNS + " FROM ai_memories WHERE user_id = ? AND session_id IS NULL"
                    + " AND" + LIVE_MEMORY + " ORDER BY created_at DESC, id DESC LIMIT ?", JdbcConversationStore::memory,
                    userId, limit);
        }
        return jdbc.query("SELECT " + MEMORY_COLUMNS + " FROM ai_memories WHERE user_id = ? AND session_id = ?"
                + " AND" + LIVE_MEMORY + " ORDER BY created_at DESC, id DESC LIMIT ?", JdbcConversationStore::memory,
                userId, sessionId, limit);
    }

    @Override
    public List<Memory> memoriesOf(long conversationId) {
        return jdbc.query("SELECT " + MEMORY_COLUMNS + " FROM ai_memories WHERE conversation_id = ? AND" + LIVE_MEMORY
                + " ORDER BY created_at DESC, id DESC", JdbcConversationStore::memory, conversationId);
    }

    @Override
    public int countMemories(long userId, Long sessionId) {
        Integer count = sessionId == null
                ? jdbc.queryForObject("SELECT count(*) FROM ai_memories WHERE user_id = ? AND session_id IS NULL"
                        + " AND" + LIVE_MEMORY, Integer.class, userId)
                : jdbc.queryForObject("SELECT count(*) FROM ai_memories WHERE user_id = ? AND session_id = ?"
                        + " AND" + LIVE_MEMORY, Integer.class, userId, sessionId);
        return count == null ? 0 : count;
    }

    @Override
    public Memory insertMemory(long userId, Long sessionId, long conversationId, String text, Instant now) {
        return jdbc.queryForObject("INSERT INTO ai_memories (user_id, session_id, conversation_id, text, created_at)"
                        + " VALUES (?, ?, ?, ?, ?) RETURNING " + MEMORY_COLUMNS,
                JdbcConversationStore::memory, userId, sessionId, conversationId, text, time(now));
    }

    @Override
    public boolean softDeleteMemory(long conversationId, long memoryId, Instant now) {
        return jdbc.update("UPDATE ai_memories SET deleted_at = ? WHERE id = ? AND conversation_id = ?"
                + " AND deleted_at IS NULL AND" + String.format(LIVE_PARENT, "ai_memories"), time(now), memoryId,
                conversationId) > 0;
    }

    @Override
    public Optional<Conversation> setSaved(long conversationId, Instant savedAt) {
        if (savedAt == null) {
            return first(jdbc.query("UPDATE ai_conversations SET saved_at = NULL WHERE id = ? AND deleted_at IS NULL"
                    + " RETURNING " + CONVERSATION_COLUMNS, JdbcConversationStore::conversation, conversationId));
        }
        // saving again keeps the first saved time
        return first(jdbc.query("UPDATE ai_conversations SET saved_at = COALESCE(saved_at, ?) WHERE id = ?"
                        + " AND deleted_at IS NULL RETURNING " + CONVERSATION_COLUMNS,
                JdbcConversationStore::conversation, time(savedAt), conversationId));
    }

    /**
     * Retention A runs against the session row too: {@code FOR SHARE OF s SKIP LOCKED} conflicts with the row lock
     * of a teacher's retention change (or the session end), which is an UPDATE of study_sessions.
     * <ul>
     *   <li>change still open: the session row is locked, so its rows are skipped in this batch (next hourly run);</li>
     *   <li>change committed after this statement's snapshot: locking the row re-evaluates the WHERE clause on the
     *       latest version (READ COMMITTED), so the new ended_at + ai_retention_days decides;</li>
     *   <li>this statement locks first: the change waits until the DELETE (autocommit, one statement) finishes.</li>
     * </ul>
     * Every lock here is SKIP LOCKED, so this statement never waits and cannot take part in a deadlock.
     */
    static final String LOCK_ROWS_AND_SESSION = " FOR UPDATE OF %s SKIP LOCKED FOR SHARE OF s SKIP LOCKED";

    /**
     * Retention A. Rows locked by a turn or a save toggle are skipped (SKIP LOCKED); the outer DELETE re-checks
     * saved_at so a conversation saved meanwhile is kept. Soft-deleted conversations are included (retention removes
     * whatever expired); a saved one that the user deleted is left to the deleted purge. The session row is share
     * locked and re-checked ({@link #LOCK_ROWS_AND_SESSION}).
     */
    @Override
    public int deleteExpiredClassConversations(Instant now, int limit) {
        return jdbc.update("DELETE FROM ai_conversations WHERE saved_at IS NULL AND id IN (SELECT c.id"
                        + " FROM ai_conversations c JOIN study_sessions s ON s.id = c.session_id"
                        + " WHERE c.saved_at IS NULL AND" + SESSION_EXPIRED
                        + " ORDER BY c.id LIMIT ?" + String.format(LOCK_ROWS_AND_SESSION, "c") + ")",
                time(now), time(now), limit);
    }

    /** Retention A: session-scoped memories (saved conversations' too, see the interface); same session lock. */
    @Override
    public int deleteExpiredClassMemories(Instant now, int limit) {
        return jdbc.update("DELETE FROM ai_memories WHERE id IN (SELECT m.id"
                        + " FROM ai_memories m JOIN study_sessions s ON s.id = m.session_id"
                        + " WHERE" + SESSION_EXPIRED
                        + " ORDER BY m.id LIMIT ?" + String.format(LOCK_ROWS_AND_SESSION, "m") + ")",
                time(now), time(now), limit);
    }

    /**
     * Retention B. Locked rows are skipped and the outer DELETE re-checks the cutoff (a turn saved meanwhile moved
     * last_message_at) and saved_at. Soft-deleted conversations are included.
     */
    @Override
    public int deleteIdlePersonalConversationsBefore(Instant cutoff, int limit) {
        return jdbc.update("DELETE FROM ai_conversations WHERE session_id IS NULL AND saved_at IS NULL"
                        + " AND last_message_at < ? AND id IN (SELECT id FROM ai_conversations"
                        + " WHERE session_id IS NULL AND saved_at IS NULL AND last_message_at < ?"
                        + " ORDER BY last_message_at, id LIMIT ? FOR UPDATE SKIP LOCKED)",
                time(cutoff), time(cutoff), limit);
    }

    /** Locked rows are skipped and the outer DELETE re-checks deleted_at. */
    @Override
    public int purgeDeletedConversationsBefore(Instant cutoff, int limit) {
        return jdbc.update("DELETE FROM ai_conversations WHERE deleted_at < ? AND id IN (SELECT id"
                        + " FROM ai_conversations WHERE deleted_at < ? ORDER BY deleted_at, id LIMIT ?"
                        + " FOR UPDATE SKIP LOCKED)",
                time(cutoff), time(cutoff), limit);
    }

    @Override
    public int purgeDeletedMemoriesBefore(Instant cutoff, int limit) {
        return jdbc.update("DELETE FROM ai_memories WHERE deleted_at < ? AND id IN (SELECT id"
                        + " FROM ai_memories WHERE deleted_at < ? ORDER BY deleted_at, id LIMIT ?"
                        + " FOR UPDATE SKIP LOCKED)",
                time(cutoff), time(cutoff), limit);
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    private static OffsetDateTime time(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Conversation conversation(ResultSet rs, int row) throws SQLException {
        return new Conversation(rs.getLong("id"), rs.getLong("user_id"), rs.getObject("session_id", Long.class),
                rs.getString("topic"), rs.getString("title"), rs.getInt("message_count"), instant(rs, "created_at"),
                instant(rs, "last_message_at"), instant(rs, "saved_at"));
    }

    private static Message message(ResultSet rs, int row) throws SQLException {
        return new Message(rs.getLong("id"), rs.getLong("conversation_id"), rs.getString("role"),
                rs.getString("content"), instant(rs, "created_at"));
    }

    private static Summary summary(ResultSet rs, int row) throws SQLException {
        return new Summary(rs.getLong("id"), rs.getLong("conversation_id"), rs.getString("text"),
                rs.getLong("from_message_id"), rs.getLong("to_message_id"), instant(rs, "created_at"),
                instant(rs, "superseded_at"), instant(rs, "invalidated_at"));
    }

    private static Memory memory(ResultSet rs, int row) throws SQLException {
        return new Memory(rs.getLong("id"), rs.getLong("user_id"), rs.getObject("session_id", Long.class),
                rs.getLong("conversation_id"), rs.getString("text"), instant(rs, "created_at"));
    }
}
