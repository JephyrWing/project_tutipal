package com.my.tutipal_back.moderation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.my.tutipal_back.moderation.ContentModerationDtos.Action;
import com.my.tutipal_back.moderation.ContentModerationDtos.Item;
import com.my.tutipal_back.moderation.ContentModerationDtos.Kind;

@Repository
class ContentModerationRepository {

    record LockedItem(Item item, String storageKey, boolean originalMissing) {
    }

    private final JdbcTemplate jdbc;

    ContentModerationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Item> list(Kind kind, Long before, int limit) {
        return jdbc.query(selection(kind) + " WHERE (?::bigint IS NULL OR id < ?) ORDER BY id DESC LIMIT ?",
                (row, index) -> item(row), before, before, limit);
    }

    Optional<LockedItem> lock(Kind kind, long id) {
        return jdbc.query(selection(kind) + " WHERE id = ? FOR UPDATE",
                (row, index) -> new LockedItem(item(row), row.getString("storage_key"),
                        row.getBoolean("original_missing")), id).stream().findFirst();
    }

    void change(Kind kind, Item item, long actorId, boolean hidden, String reason) {
        long version = item.version() + 1;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("UPDATE " + table(kind) + " SET hidden_at = ?, moderation_version = ? WHERE id = ?",
                hidden ? now : null, version, item.id());
        jdbc.update("""
                INSERT INTO content_moderation_actions(kind, content_id, session_id, actor_id, version, hidden, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, kind.name(), item.id(), item.sessionId(), actorId, version, hidden, reason, now);
    }

    List<Action> history(Kind kind, long id, int limit) {
        return jdbc.query("""
                SELECT version, actor_id, hidden, reason, created_at
                FROM content_moderation_actions
                WHERE kind = ? AND content_id = ?
                ORDER BY version DESC LIMIT ?
                """, (row, index) -> new Action(row.getLong("version"), row.getLong("actor_id"),
                        row.getBoolean("hidden"), row.getString("reason"), instant(row, "created_at")),
                kind.name(), id, limit);
    }

    private static String selection(Kind kind) {
        return switch (kind) {
            case POST -> """
                    SELECT id, session_id, title AS label, hidden_at, moderation_version,
                        NULL AS storage_key, FALSE AS original_missing FROM posts
                    """;
            case MATERIAL -> """
                    SELECT id, session_id, filename AS label, hidden_at, moderation_version,
                        storage_key, original_missing FROM materials
                    """;
        };
    }

    private static String table(Kind kind) {
        return switch (kind) {
            case POST -> "posts";
            case MATERIAL -> "materials";
        };
    }

    private static Item item(ResultSet row) throws SQLException {
        return new Item(row.getLong("id"), row.getLong("session_id"), row.getString("label"),
                instant(row, "hidden_at"), row.getLong("moderation_version"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
