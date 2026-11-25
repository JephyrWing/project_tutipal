package com.my.tutipal_back.livekit.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.livekit.dto.HelpChatDtos.Message;

/** Caller owns the transaction. All mutation paths lock session before help. */
@Repository
public class HelpChatStore {
    public record Context(long sessionId, long studentId, Long teacherId, String status, boolean ended) {}
    private final JdbcTemplate jdbc;
    public HelpChatStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Context lock(long helpId) {
        List<Long> ids = jdbc.queryForList("SELECT session_id FROM help_requests WHERE id=?", Long.class, helpId);
        if (ids.isEmpty()) throw missing();
        long sessionId = ids.getFirst();
        List<Boolean> ended = jdbc.query("SELECT ended_at IS NOT NULL FROM study_sessions WHERE id=? FOR UPDATE",
                (rs,n) -> rs.getBoolean(1), sessionId);
        if (ended.isEmpty()) throw missing();
        return jdbc.query("SELECT user_id,accepted_by_user_id,status FROM help_requests WHERE id=? FOR UPDATE",
                (rs,n) -> new Context(sessionId, rs.getLong(1), rs.getObject(2,Long.class), rs.getString(3), ended.getFirst()), helpId)
                .stream().findFirst().orElseThrow(HelpChatStore::missing);
    }
    public Optional<String> role(long sessionId, long userId) {
        return jdbc.queryForList("SELECT role FROM session_participants WHERE session_id=? AND user_id=?", String.class, sessionId,userId)
                .stream().findFirst();
    }
    public Optional<Message> previous(long helpId,long userId,UUID clientId) {
        return jdbc.query("SELECT * FROM help_chat_messages WHERE help_id=? AND sender_id=? AND client_message_id=?",
                HelpChatStore::message,helpId,userId,clientId).stream().findFirst();
    }
    public Message insert(long helpId,long userId,UUID clientId,String text) {
        Long sequence=jdbc.queryForObject("UPDATE help_requests SET next_chat_sequence=next_chat_sequence+1 WHERE id=? RETURNING next_chat_sequence-1",Long.class,helpId);
        return jdbc.queryForObject("INSERT INTO help_chat_messages(help_id,sequence,sender_id,client_message_id,message_text) VALUES(?,?,?,?,?) RETURNING *",
                HelpChatStore::message,helpId,sequence,userId,clientId,text);
    }
    public List<Message> after(long helpId,long cursor,int limit) {
        return jdbc.query("SELECT * FROM help_chat_messages WHERE help_id=? AND sequence>? ORDER BY sequence LIMIT ?",
                HelpChatStore::message,helpId,cursor,limit);
    }
    private static Message message(ResultSet rs,int row) throws SQLException {
        return new Message(rs.getString("id"),rs.getString("help_id"),rs.getString("sequence"),rs.getString("sender_id"),
                rs.getObject("client_message_id",UUID.class),rs.getString("message_text"),rs.getTimestamp("created_at").toInstant());
    }
    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"도움 요청을 찾을 수 없어요"); }
}
