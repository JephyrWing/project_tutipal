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

/** PT-106 Round 1 (developer test): non-locking lookup and the memory scope advisory lock, SQL shape only. */
class JdbcConversationStoreRound1SqlTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JdbcConversationStore store = new JdbcConversationStore(jdbc);

    private List<String> sql() {
        List<String> out = new ArrayList<>();
        for (Invocation i : mockingDetails(jdbc).getInvocations()) {
            for (Object arg : i.getRawArguments()) {
                if (arg instanceof String s && s.toLowerCase(Locale.ROOT).startsWith("select")) {
                    out.add(s.toLowerCase(Locale.ROOT));
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
    void findDoesNotLock() {
        try {
            store.find(7L);
        } catch (RuntimeException ignored) {
            // mock result
        }

        assertThat(sql()).singleElement().satisfies(s -> assertThat(s).contains("ai_conversations")
                .doesNotContain("for update"));
    }

    @Test
    void memoryScopeLockIsATransactionAdvisoryLockPerUserAndScope() {
        store.lockMemoryScope(2L, 10L);
        store.lockMemoryScope(2L, null);

        assertThat(sql()).hasSize(2).allSatisfy(s -> assertThat(s).contains("pg_advisory_xact_lock"));
        assertThat(args()).contains("ai_memories:2:10", "ai_memories:2:personal");
    }
}
