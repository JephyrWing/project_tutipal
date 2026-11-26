package com.my.tutipal_back.learning.conversation.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PT-106 SQL shape of JdbcConversationStore without a database (real Postgres not run in JUnit):
 * the active summary filter (AC-11), FOR UPDATE before writes and a single cascading delete (AC-9), and the
 * memory scope filter by (userId, sessionId | personal) (AC-7).
 */
class JdbcConversationStoreSqlTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JdbcConversationStore store = new JdbcConversationStore(jdbc);

    /** Runs the call (a mock JdbcTemplate may make the mapping fail; only the SQL sent matters here). */
    private void quietly(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException ignored) {
            // the mock returns null / empty results
        }
    }

    private List<Invocation> invocations() {
        return new ArrayList<>(mockingDetails(jdbc).getInvocations());
    }

    private static List<String> sql(List<Invocation> invocations) {
        List<String> out = new ArrayList<>();
        for (Invocation i : invocations) {
            for (Object arg : i.getRawArguments()) {
                if (arg instanceof String s && s.toLowerCase(Locale.ROOT).matches("(?s)\\s*(select|insert|update|delete|with)\\b.*")) {
                    out.add(s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " "));
                }
            }
        }
        return out;
    }

    private static List<Object> args(List<Invocation> invocations) {
        List<Object> out = new ArrayList<>();
        for (Invocation i : invocations) {
            for (Object arg : i.getRawArguments()) {
                if (arg instanceof Object[] array) {
                    out.addAll(java.util.Arrays.asList(array));
                } else {
                    out.add(arg);
                }
            }
        }
        return out;
    }

    @Test
    void activeSummaryQueryFiltersSupersededAndInvalidated() {
        quietly(() -> store.activeSummary(7L));

        List<String> sent = sql(invocations());
        assertThat(sent).isNotEmpty();
        assertThat(sent).anySatisfy(s -> assertThat(s).contains("ai_conversation_summaries")
                .contains("superseded_at is null").contains("invalidated_at is null"));
        assertThat(args(invocations())).contains(7L);
    }

    @Test
    void lockOwnedSelectsForUpdateByOwnerAndId() {
        quietly(() -> store.lockOwned(2L, 7L));

        assertThat(sql(invocations())).anySatisfy(s -> assertThat(s).contains("ai_conversations").contains("for update"));
        assertThat(args(invocations())).contains(2L, 7L);
    }

    @Test
    void lockByIdSelectsForUpdate() {
        quietly(() -> store.lock(7L));

        assertThat(sql(invocations())).anySatisfy(s -> assertThat(s).contains("for update"));
    }

    /** PM decision: the user's delete is a soft delete; the physical DELETE (CASCADE) is the purge job's. */
    @Test
    void deleteConversationIsOneUpdateOfDeletedAt() {
        quietly(() -> store.softDeleteConversation(7L, java.time.Instant.parse("2026-11-25T00:00:00Z")));

        List<String> sent = sql(invocations());
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst()).startsWith("update ai_conversations set deleted_at = ?")
                .contains("deleted_at is null").doesNotContain("delete from");
        assertThat(args(invocations())).contains(7L);
    }

    @Test
    void sessionMemoriesAreFilteredByUserAndSessionNewestFirst() {
        quietly(() -> store.memories(2L, 10L, 20));

        assertThat(sql(invocations())).anySatisfy(s -> assertThat(s).contains("ai_memories").contains("user_id")
                .contains("session_id").contains("created_at desc").doesNotContain("session_id is null"));
        assertThat(args(invocations())).contains(2L, 10L);
    }

    @Test
    void personalMemoriesAreThoseWithoutSession() {
        quietly(() -> store.memories(2L, null, 20));

        assertThat(sql(invocations())).anySatisfy(s -> assertThat(s).contains("ai_memories")
                .contains("session_id is null").contains("created_at desc"));
        assertThat(args(invocations())).contains(2L);
    }

    @Test
    void personalConversationListIsThoseWithoutSession() {
        quietly(() -> store.list(2L, null, null, 21));

        assertThat(sql(invocations())).anySatisfy(s -> assertThat(s).contains("ai_conversations")
                .contains("session_id is null").contains("last_message_at desc"));
    }
}
