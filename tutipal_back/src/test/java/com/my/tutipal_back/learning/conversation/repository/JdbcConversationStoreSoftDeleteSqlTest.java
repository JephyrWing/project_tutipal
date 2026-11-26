package com.my.tutipal_back.learning.conversation.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.invocation.Invocation;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PT-106 soft delete, SQL shape only (no database in JUnit): every live read filters deleted_at (the conversation's
 * own, or its parent conversation's for messages / summaries / memories), memory delete is an UPDATE, and the purge
 * statements skip locked rows and re-check deleted_at in the outer DELETE.
 */
class JdbcConversationStoreSoftDeleteSqlTest {

    private static final Instant NOW = Instant.parse("2026-11-25T00:00:00Z");

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
                if (arg instanceof String s && s.toLowerCase(Locale.ROOT).matches("(?s)\\s*(select|update|delete)\\b.*")) {
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

    static List<Arguments> conversationReads() {
        return List.of(
                Arguments.of("findOwned", (Consumer<JdbcConversationStore>) s -> s.findOwned(2L, 7L)),
                Arguments.of("lockOwned", (Consumer<JdbcConversationStore>) s -> s.lockOwned(2L, 7L)),
                Arguments.of("find", (Consumer<JdbcConversationStore>) s -> s.find(7L)),
                Arguments.of("lock", (Consumer<JdbcConversationStore>) s -> s.lock(7L)),
                Arguments.of("list personal", (Consumer<JdbcConversationStore>) s -> s.list(2L, null, null, 21)),
                Arguments.of("list session cursor", (Consumer<JdbcConversationStore>) s -> s.list(2L, 10L,
                        new ConversationStore.Cursor(NOW, 9L), 21)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("conversationReads")
    void conversationReadsSeeOnlyLiveConversations(String name, Consumer<JdbcConversationStore> call) {
        assertThat(sent(call)).singleElement().satisfies(s -> assertThat(s)
                .contains("from ai_conversations").contains("deleted_at is null"));
    }

    static List<Arguments> childReads() {
        return List.of(
                Arguments.of("messagesBefore first page", (Consumer<JdbcConversationStore>) s ->
                        s.messagesBefore(7L, null, 51)),
                Arguments.of("messagesBefore", (Consumer<JdbcConversationStore>) s -> s.messagesBefore(7L, 40L, 51)),
                Arguments.of("recentMessagesAfter", (Consumer<JdbcConversationStore>) s ->
                        s.recentMessagesAfter(7L, 0L, 39)),
                Arguments.of("countMessagesAfter", (Consumer<JdbcConversationStore>) s -> s.countMessagesAfter(7L, 0L)),
                Arguments.of("oldestMessagesAfter", (Consumer<JdbcConversationStore>) s ->
                        s.oldestMessagesAfter(7L, 0L, 40)),
                Arguments.of("findPairByClientMessageId", (Consumer<JdbcConversationStore>) s ->
                        s.findPairByClientMessageId(7L, UUID.fromString("00000000-0000-4000-8000-000000000106"))),
                Arguments.of("activeSummary", (Consumer<JdbcConversationStore>) s -> s.activeSummary(7L)),
                Arguments.of("findSummary", (Consumer<JdbcConversationStore>) s -> s.findSummary(3L)));
    }

    /** Messages and summaries of a deleted conversation never reach a response, a summary input or an Agent request. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("childReads")
    void messageAndSummaryReadsRequireALiveConversation(String name, Consumer<JdbcConversationStore> call) {
        assertThat(sent(call)).singleElement().satisfies(s -> assertThat(s)
                .contains("exists (select 1 from ai_conversations live where live.id = ")
                .contains(".conversation_id and live.deleted_at is null)"));
    }

    static List<Arguments> memoryReads() {
        return List.of(
                Arguments.of("memories personal", (Consumer<JdbcConversationStore>) s -> s.memories(2L, null, 20)),
                Arguments.of("memories session", (Consumer<JdbcConversationStore>) s -> s.memories(2L, 10L, 20)),
                Arguments.of("memoriesOf", (Consumer<JdbcConversationStore>) s -> s.memoriesOf(7L)),
                Arguments.of("countMemories personal", (Consumer<JdbcConversationStore>) s -> s.countMemories(2L, null)),
                Arguments.of("countMemories session", (Consumer<JdbcConversationStore>) s -> s.countMemories(2L, 10L)));
    }

    /** Recall, detail and the 20 cap: live memories of live conversations only. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("memoryReads")
    void memoryReadsSkipDeletedMemoriesAndMemoriesOfDeletedConversations(String name,
                                                                          Consumer<JdbcConversationStore> call) {
        assertThat(sent(call)).singleElement().satisfies(s -> assertThat(s)
                .contains("from ai_memories")
                .contains("ai_memories.deleted_at is null")
                .contains("live.id = ai_memories.conversation_id and live.deleted_at is null"));
    }

    @Test
    void memoryDeleteIsAnUpdateOfDeletedAtWithinTheConversation() {
        List<String> sql = sent(s -> s.softDeleteMemory(7L, 3L, NOW));

        assertThat(sql).singleElement().satisfies(s -> assertThat(s)
                .startsWith("update ai_memories set deleted_at = ?")
                .contains("id = ?").contains("conversation_id = ?").contains("deleted_at is null")
                .contains("live.id = ai_memories.conversation_id and live.deleted_at is null")
                .doesNotContain("delete from"));
        assertThat(args()).contains(3L, 7L);
    }

    @Test
    void conversationPurgeSkipsLockedRowsAndRechecksDeletedAt() {
        List<String> sql = sent(s -> s.purgeDeletedConversationsBefore(NOW, 500));

        assertThat(sql).singleElement().satisfies(s -> {
            assertThat(s).startsWith("delete from ai_conversations where deleted_at < ? and id in (select id"
                    + " from ai_conversations where deleted_at < ?");
            assertThat(s).contains("order by deleted_at, id limit ?").contains("for update skip locked");
        });
        assertThat(args()).filteredOn(a -> a instanceof OffsetDateTime).hasSize(2);
        assertThat(args()).contains(500);
    }

    @Test
    void memoryPurgeSkipsLockedRowsAndRechecksDeletedAt() {
        List<String> sql = sent(s -> s.purgeDeletedMemoriesBefore(NOW, 500));

        assertThat(sql).singleElement().satisfies(s -> {
            assertThat(s).startsWith("delete from ai_memories where deleted_at < ? and id in (select id"
                    + " from ai_memories where deleted_at < ?");
            assertThat(s).contains("order by deleted_at, id limit ?").contains("for update skip locked");
        });
        assertThat(args()).filteredOn(a -> a instanceof OffsetDateTime).hasSize(2);
    }

    /** Retention removes expired conversations whether or not the user already deleted them. */
    @Test
    void personalRetentionAlsoCoversSoftDeletedConversations() {
        List<String> sql = sent(s -> s.deleteIdlePersonalConversationsBefore(NOW, 500));

        assertThat(sql).singleElement().satisfies(s -> assertThat(s).doesNotContain("deleted_at"));
    }

    @Test
    void classRetentionAlsoCoversSoftDeletedConversations() {
        List<String> sql = sent(s -> s.deleteExpiredClassConversations(NOW, 500));

        assertThat(sql).singleElement().satisfies(s -> assertThat(s).doesNotContain("deleted_at"));
    }

    /** Saving or unsaving a deleted conversation changes nothing (404 upstream). */
    @Test
    void saveToggleOnlyTouchesLiveConversations() {
        assertThat(sent(s -> s.setSaved(7L, NOW))).singleElement().satisfies(s -> assertThat(s)
                .startsWith("update ai_conversations set saved_at = coalesce(saved_at, ?)")
                .contains("deleted_at is null"));
    }
}
