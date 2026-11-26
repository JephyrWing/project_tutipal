package com.my.tutipal_back.learning.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PT-106 / PT-13 V24 text check (the real PostgreSQL run is ConversationRetentionPostgresTest): the per-session
 * retention column with its 0..90 CHECK, the 90-day backfill of existing sessions, saved_at, and the retention
 * indexes.
 */
class ConversationRetentionMigrationTest {

    private static final String PATH = "/db/migration/V24__ai_conversation_retention.sql";
    private String sql;

    @BeforeEach
    void load() throws IOException {
        try (InputStream in = ConversationRetentionMigrationTest.class.getResourceAsStream(PATH)) {
            assertThat(in).as(PATH + " on the classpath").isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("--[^\n]*", " ")
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("\\s+", " ");
        }
    }

    @Test
    void sessionsGetANullableRetentionColumnLimitedToZeroToNinety() {
        assertThat(sql).contains("alter table study_sessions add column ai_retention_days smallint")
                .contains("check (ai_retention_days between 0 and 90)");
        assertThat(sql).doesNotContain("ai_retention_days smallint not null");
    }

    /** Existing sessions get the maximum so nothing stored before the decision is deleted earlier than allowed. */
    @Test
    void existingSessionsAreBackfilledWithTheMaximum() {
        assertThat(sql).contains("update study_sessions set ai_retention_days = 90 where ai_retention_days is null");
    }

    @Test
    void conversationsGetANullableSavedAt() {
        assertThat(sql).contains("alter table ai_conversations add column saved_at timestamp(6) with time zone");
    }

    @Test
    void retentionIndexesCoverOnlyUnsavedRowsAndEndedSessions() {
        assertThat(sql).contains("on ai_conversations (last_message_at, id) where session_id is null and saved_at"
                + " is null");
        assertThat(sql).contains("on ai_conversations (session_id, id) where session_id is not null and saved_at"
                + " is null");
        assertThat(sql).contains("on study_sessions (ended_at) where ended_at is not null");
        assertThat(sql).contains("drop index if exists idx_ai_conversations_last_message");
    }
}
