package com.my.tutipal_back.learning.conversation.repository;

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

/** PT-106 ConversationStore on PostgreSQL. The caller owns the transaction; writes lock the conversation row first. */
@Repository
public class JdbcConversationStore implements ConversationStore {

    private static final String CONVERSATION_COLUMNS =
            "id, user_id, session_id, topic, title, message_count, created_at, last_message_at";
    private static final String MESSAGE_COLUMNS = "id, conversation_id, role, content, created_at";
    private static final String SUMMARY_COLUMNS =
            "id, conversation_id, text, from_message_id, to_message_id, created_at, superseded_at, invalidated_at";
    private static final String MEMORY_COLUMNS = "id, user_id, session_id, conversation_id, text, created_at";

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
        return first(jdbc.query("SELECT " + CONVERSATION_COLUMNS + " FROM ai_conversations WHERE id = ? AND user_id = ?",
                JdbcConversationStore::conversation, conversationId, userId));
    }

    @Override
    public Optional<Conversation> lockOwned(long userId, long conversationId) {
        return first(jdbc.query("SELECT " + CONVERSATION_COLUMNS
                        + " FROM ai_conversations WHERE id = ? AND user_id = ? FOR UPDATE",
                JdbcConversationStore::conversation, conversationId, userId));
    }

    @Override
    public Optional<Conversation> lock(long conversationId) {
        return first(jdbc.query("SELECT " + CONVERSATION_COLUMNS + " FROM ai_conversations WHERE id = ? FOR UPDATE",
                JdbcConversationStore::conversation, conversationId));
    }

    @Override
    public List<Conversation> list(long userId, Long sessionId, Cursor after, int limit) {
        String scope = sessionId == null ? "session_id IS NULL" : "session_id = ?";
        String keyset = after == null ? "" : " AND (last_message_at, id) < (?, ?)";
        String sql = "SELECT " + CONVERSATION_COLUMNS + " FROM ai_conversations WHERE user_id = ? AND " + scope + keyset
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
    public void deleteConversation(long conversationId) {
        jdbc.update("DELETE FROM ai_conversations WHERE id = ?", conversationId);
    }

    @Override
    public List<Message> messagesBefore(long conversationId, Long beforeId, int limit) {
        if (beforeId == null) {
            return jdbc.query("SELECT " + MESSAGE_COLUMNS + " FROM ai_conversation_messages WHERE conversation_id = ?"
                    + " ORDER BY id DESC LIMIT ?", JdbcConversationStore::message, conversationId, limit);
        }
        return jdbc.query("SELECT " + MESSAGE_COLUMNS + " FROM ai_conversation_messages WHERE conversation_id = ?"
                + " AND id < ? ORDER BY id DESC LIMIT ?", JdbcConversationStore::message, conversationId, beforeId, limit);
    }

    @Override
    public List<Message> recentMessagesAfter(long conversationId, long afterMessageId, int limit) {
        return jdbc.query("SELECT " + MESSAGE_COLUMNS + " FROM (SELECT " + MESSAGE_COLUMNS
                        + " FROM ai_conversation_messages WHERE conversation_id = ? AND id > ? ORDER BY id DESC LIMIT ?)"
                        + " recent ORDER BY id",
                JdbcConversationStore::message, conversationId, afterMessageId, limit);
    }

    @Override
    public int countMessagesAfter(long conversationId, long afterMessageId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM ai_conversation_messages WHERE conversation_id = ? AND id > ?",
                Integer.class, conversationId, afterMessageId);
        return count == null ? 0 : count;
    }

    @Override
    public List<Message> oldestMessagesAfter(long conversationId, long afterMessageId, int limit) {
        return jdbc.query("SELECT " + MESSAGE_COLUMNS + " FROM ai_conversation_messages WHERE conversation_id = ?"
                + " AND id > ? ORDER BY id LIMIT ?", JdbcConversationStore::message, conversationId, afterMessageId, limit);
    }

    @Override
    public SavedPair insertPair(long conversationId, String question, String answer, String title, Instant now) {
        Message user = insertMessage(conversationId, "user", question, now);
        Message assistant = insertMessage(conversationId, "assistant", answer, now);
        jdbc.update("UPDATE ai_conversations SET message_count = message_count + 2, last_message_at = ?,"
                + " title = COALESCE(title, ?) WHERE id = ?", time(now), title, conversationId);
        return new SavedPair(user, assistant);
    }

    private Message insertMessage(long conversationId, String role, String content, Instant now) {
        return jdbc.queryForObject("INSERT INTO ai_conversation_messages (conversation_id, role, content, created_at)"
                        + " VALUES (?, ?, ?, ?) RETURNING " + MESSAGE_COLUMNS,
                JdbcConversationStore::message, conversationId, role, content, time(now));
    }

    @Override
    public Optional<Summary> activeSummary(long conversationId) {
        return first(jdbc.query("SELECT " + SUMMARY_COLUMNS + " FROM ai_conversation_summaries"
                        + " WHERE conversation_id = ? AND superseded_at IS NULL AND invalidated_at IS NULL",
                JdbcConversationStore::summary, conversationId));
    }

    @Override
    public Optional<Summary> findSummary(long summaryId) {
        return first(jdbc.query("SELECT " + SUMMARY_COLUMNS + " FROM ai_conversation_summaries WHERE id = ?",
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
                    + " ORDER BY created_at DESC, id DESC LIMIT ?", JdbcConversationStore::memory, userId, limit);
        }
        return jdbc.query("SELECT " + MEMORY_COLUMNS + " FROM ai_memories WHERE user_id = ? AND session_id = ?"
                + " ORDER BY created_at DESC, id DESC LIMIT ?", JdbcConversationStore::memory, userId, sessionId, limit);
    }

    @Override
    public List<Memory> memoriesOf(long conversationId) {
        return jdbc.query("SELECT " + MEMORY_COLUMNS + " FROM ai_memories WHERE conversation_id = ?"
                + " ORDER BY created_at DESC, id DESC", JdbcConversationStore::memory, conversationId);
    }

    @Override
    public int countMemories(long userId, Long sessionId) {
        Integer count = sessionId == null
                ? jdbc.queryForObject("SELECT count(*) FROM ai_memories WHERE user_id = ? AND session_id IS NULL",
                        Integer.class, userId)
                : jdbc.queryForObject("SELECT count(*) FROM ai_memories WHERE user_id = ? AND session_id = ?",
                        Integer.class, userId, sessionId);
        return count == null ? 0 : count;
    }

    @Override
    public Memory insertMemory(long userId, Long sessionId, long conversationId, String text, Instant now) {
        return jdbc.queryForObject("INSERT INTO ai_memories (user_id, session_id, conversation_id, text, created_at)"
                        + " VALUES (?, ?, ?, ?, ?) RETURNING " + MEMORY_COLUMNS,
                JdbcConversationStore::memory, userId, sessionId, conversationId, text, time(now));
    }

    @Override
    public boolean deleteMemory(long conversationId, long memoryId) {
        return jdbc.update("DELETE FROM ai_memories WHERE id = ? AND conversation_id = ?", memoryId, conversationId) > 0;
    }

    @Override
    public int deleteInactiveBefore(Instant cutoff, int limit) {
        return jdbc.update("DELETE FROM ai_conversations WHERE id IN (SELECT id FROM ai_conversations"
                + " WHERE last_message_at < ? ORDER BY last_message_at, id LIMIT ?)", time(cutoff), limit);
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
                instant(rs, "last_message_at"));
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
