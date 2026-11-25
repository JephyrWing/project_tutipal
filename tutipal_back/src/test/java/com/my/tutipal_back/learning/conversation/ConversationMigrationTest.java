package com.my.tutipal_back.learning.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PT-106 AC-13 (text check only; the real Postgres run is recorded as not run): V21__ai_conversations.sql
 * deletes everything with the user / session through ON DELETE CASCADE, and keeps at most one active summary
 * per conversation with a partial UNIQUE index.
 */
class ConversationMigrationTest {

    private static final String PATH = "/db/migration/V21__ai_conversations.sql";
    private List<String> statements;

    @BeforeEach
    void load() throws IOException {
        try (InputStream in = ConversationMigrationTest.class.getResourceAsStream(PATH)) {
            assertThat(in).as(PATH + " on the classpath").isNotNull();
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("--[^\n]*", " ")
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("\\s+", " ");
            statements = Arrays.stream(sql.split(";")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        }
    }

    /** Statements that create or alter the table (column definitions and FKs live there). */
    private String table(String name) {
        Pattern about = Pattern.compile("^(create table( if not exists)? " + name + "\\b|alter table( only)? "
                + name + "\\b)");
        String joined = String.join(" ; ", statements.stream().filter(s -> about.matcher(s).find()).toList());
        assertThat(joined).as("CREATE TABLE " + name).isNotEmpty();
        return joined;
    }

    /** column → referenced table with ON DELETE CASCADE, inline or as a table constraint. */
    private static boolean cascades(String table, String column, String referenced) {
        Pattern inline = Pattern.compile("\\b" + column + "\\b[^,;]*?references " + referenced
                + "\\b[^,;]*?on delete cascade");
        Pattern constraint = Pattern.compile("foreign key \\(" + column + "\\) references " + referenced
                + "\\b[^,;]*?on delete cascade");
        return inline.matcher(table).find() || constraint.matcher(table).find();
    }

    @Test
    void conversationsCascadeFromUsersAndSessions() {
        String t = table("ai_conversations");

        assertThat(cascades(t, "user_id", "users")).as("ai_conversations.user_id → users ON DELETE CASCADE").isTrue();
        assertThat(cascades(t, "session_id", "study_sessions"))
                .as("ai_conversations.session_id → study_sessions ON DELETE CASCADE").isTrue();
    }

    @Test
    void messagesCascadeFromConversationsAndLimitRoleAndLength() {
        String t = table("ai_conversation_messages");

        assertThat(cascades(t, "conversation_id", "ai_conversations")).isTrue();
        assertThat(t).contains("'user'").contains("'assistant'").contains("32000");
    }

    @Test
    void summariesCascadeFromConversationsAndKeepHistoryColumns() {
        String t = table("ai_conversation_summaries");

        assertThat(cascades(t, "conversation_id", "ai_conversations")).isTrue();
        assertThat(t).contains("superseded_at").contains("invalidated_at").contains("from_message_id")
                .contains("to_message_id").contains("4000");
    }

    @Test
    void atMostOneActiveSummaryPerConversation() {
        Pattern partialUnique = Pattern.compile("create unique index( if not exists)? \\w+ on ai_conversation_summaries"
                + "( using btree)? ?\\( ?conversation_id ?\\) where (\\()?"
                + "(superseded_at is null and invalidated_at is null|invalidated_at is null and superseded_at is null)");

        assertThat(statements).anyMatch(s -> partialUnique.matcher(s).find());
    }

    @Test
    void memoriesCascadeFromUsersSessionsAndConversations() {
        String t = table("ai_memories");

        assertThat(cascades(t, "user_id", "users")).isTrue();
        assertThat(cascades(t, "session_id", "study_sessions")).isTrue();
        assertThat(cascades(t, "conversation_id", "ai_conversations")).isTrue();
        assertThat(t).contains("1000");
    }

    @Test
    void migrationDoesNotTouchExistingTables() {
        Pattern foreign = Pattern.compile("^(alter|drop|update|delete|truncate)\\b(?!.*\\bai_(conversations|"
                + "conversation_messages|conversation_summaries|memories)\\b)");

        assertThat(statements).noneMatch(s -> foreign.matcher(s).find());
    }
}
