package com.my.tutipal_back.learning.conversation.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PT-106 retention (PT-13 PM decision 2026-11-26), SQL shape only (no database in JUnit; the real PostgreSQL run is
 * ConversationRetentionPostgresTest): class retention joins study_sessions and counts ended_at + ai_retention_days
 * (NULL = 90), skips saved rows and open sessions; personal retention is scoped to session_id IS NULL and unsaved;
 * every DELETE skips locked rows and re-checks saved_at in the outer statement.
 */
class JdbcConversationStoreRetentionSqlTest {

    private static final Instant NOW = Instant.parse("2026-11-26T00:00:00Z");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JdbcConversationStore store = new JdbcConversationStore(jdbc);

    private List<String> sent(Consumer<JdbcConversationStore> call) {
        try {
            call.accept(store);
        } catch (RuntimeException ignored) {
            // the mock returns null / empty results
        }
        List<String> out = new ArrayList<>();
        for (Invocation i : mockingDetails(jdbc).getInvocations()) {
            for (Object arg : i.getRawArguments()) {
                if (arg instanceof String s) {
                    out.add(s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " "));
                }
            }
        }
        return out;
    }

    private List<Object> args() {
        List<Object> out = new ArrayList<>();
        for (Invocation i : mockingDetails(jdbc).getInvocations()) {
            for (Object arg : i.getRawArguments()) {
                if (arg instanceof Object[] array) {
                    out.addAll(List.of(array));
                } else {
                    out.add(arg);
                }
            }
        }
        return out;
    }

    @Test
    void classRetentionDeletesUnsavedConversationsOfSessionsPastTheirRetention() {
        List<String> sql = sent(s -> s.deleteExpiredClassConversations(NOW, 500));

        assertThat(sql).singleElement().satisfies(s -> {
            assertThat(s).startsWith("delete from ai_conversations where saved_at is null and id in (select c.id");
            assertThat(s).contains("join study_sessions s on s.id = c.session_id");
            assertThat(s).contains("c.saved_at is null");
            assertThat(s).contains("s.ended_at is not null");
            assertThat(s).contains("s.ended_at + coalesce(s.ai_retention_days, 90) * interval '1 day' < ?");
            assertThat(s).contains("for update of c skip locked");
        });
        assertThat(args()).filteredOn(a -> a instanceof OffsetDateTime)
                .containsExactly(NOW.atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC));
        assertThat(args()).contains(500);
    }

    /** Session-scoped memories follow the session (saved conversations' memories too): no saved_at condition. */
    @Test
    void classMemoryRetentionDeletesSessionScopedMemoriesOfExpiredSessions() {
        List<String> sql = sent(s -> s.deleteExpiredClassMemories(NOW, 500));

        assertThat(sql).singleElement().satisfies(s -> {
            assertThat(s).startsWith("delete from ai_memories where id in (select m.id");
            assertThat(s).contains("join study_sessions s on s.id = m.session_id");
            assertThat(s).contains("s.ended_at is not null");
            assertThat(s).contains("coalesce(s.ai_retention_days, 90)");
            assertThat(s).contains("for update of m skip locked");
            assertThat(s).doesNotContain("saved_at");
        });
    }

    @Test
    void personalRetentionIsScopedToUnsavedPersonalConversationsAndRechecksInTheOuterDelete() {
        List<String> sql = sent(s -> s.deleteIdlePersonalConversationsBefore(NOW, 500));

        assertThat(sql).singleElement().satisfies(s -> {
            String outer = s.substring(0, s.indexOf("id in ("));
            String inner = s.substring(s.indexOf("id in ("));
            assertThat(outer).contains("session_id is null").contains("saved_at is null")
                    .contains("last_message_at < ?");
            assertThat(inner).contains("session_id is null").contains("saved_at is null")
                    .contains("order by last_message_at, id limit ?").contains("for update skip locked");
        });
    }

    @Test
    void unsaveClearsSavedAtOfALiveConversation() {
        assertThat(sent(s -> s.setSaved(7L, null))).singleElement().satisfies(s -> assertThat(s)
                .startsWith("update ai_conversations set saved_at = null where id = ? and deleted_at is null")
                .contains("returning"));
    }

    @Test
    void conversationRowsCarrySavedAt() {
        assertThat(sent(s -> s.findOwned(2L, 7L))).singleElement()
                .satisfies(s -> assertThat(s).contains("saved_at"));
    }
}
