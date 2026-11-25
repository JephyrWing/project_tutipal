package com.my.tutipal_back.learning.conversation.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PT-106 Codex re-review (developer test): SQL shape of the idempotency key without a database. The question row
 * is inserted with client_message_id, the answer row without it, the lookup is scoped to the conversation, and V21
 * declares the UUID column with a partial UNIQUE (conversation_id, client_message_id).
 */
class JdbcConversationStoreIdempotencySqlTest {

    private static final UUID KEY = UUID.fromString("9f8e7d6c-5b4a-4321-8fed-cba987654321");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JdbcConversationStore store = new JdbcConversationStore(jdbc);

    private static String normalized(String sql) {
        return sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** (sql, flattened args) of every JdbcTemplate call, in order. */
    private List<Object[]> statements() {
        List<Object[]> out = new ArrayList<>();
        for (Invocation i : mockingDetails(jdbc).getInvocations()) {
            String sql = null;
            List<Object> args = new ArrayList<>();
            for (Object arg : i.getRawArguments()) {
                if (sql == null && arg instanceof String s) {
                    sql = normalized(s);
                } else if (arg instanceof Object[] array) {
                    args.addAll(Arrays.asList(array));
                } else {
                    args.add(arg);
                }
            }
            out.add(new Object[] {sql, args});
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> argsOf(Object[] statement) {
        return (List<Object>) statement[1];
    }

    @Test
    void insertPairStoresTheKeyOnTheQuestionRowOnly() {
        store.insertPair(7L, "질문", "답", "질문", Instant.parse("2026-11-25T00:00:00Z"), KEY);

        List<Object[]> inserts = statements().stream()
                .filter(s -> ((String) s[0]).startsWith("insert into ai_conversation_messages")).toList();
        assertThat(inserts).hasSize(2);
        assertThat((String) inserts.get(0)[0]).contains("client_message_id");
        assertThat(argsOf(inserts.get(0))).contains("user", KEY);
        assertThat(argsOf(inserts.get(1))).contains("assistant").doesNotContain(KEY);
        assertThat(argsOf(inserts.get(1)).getLast()).isNull();
    }

    @Test
    void lookupIsScopedToTheConversationAndReadsTheQuestionAndItsAnswer() {
        Object result = store.findPairByClientMessageId(7L, KEY);

        assertThat(result).isEqualTo(java.util.Optional.empty());
        Object[] query = statements().getFirst();
        assertThat((String) query[0]).contains("from ai_conversation_messages").contains("client_message_id = ?")
                .contains("role = 'user'").contains("order by id limit 2");
        assertThat(argsOf(query)).contains(7L, KEY);
        assertThat(argsOf(query).stream().filter(a -> Long.valueOf(7L).equals(a)).count()).isEqualTo(2);
    }

    @Test
    void migrationAddsTheUuidColumnWithAPartialUniqueIndex() throws IOException {
        String sql;
        try (InputStream in = getClass().getResourceAsStream("/db/migration/V21__ai_conversations.sql")) {
            assertThat(in).isNotNull();
            sql = normalized(new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("--[^\n]*", " "));
        }
        String messages = sql.substring(sql.indexOf("create table ai_conversation_messages"));
        messages = messages.substring(0, messages.indexOf(");"));

        assertThat(messages).containsPattern("client_message_id uuid( null)?,");
        assertThat(messages).doesNotContainPattern("client_message_id uuid not null");
        assertThat(messages).contains("check (client_message_id is null or role = 'user')");
        assertThat(Pattern.compile("create unique index \\w+ on ai_conversation_messages ?\\( ?conversation_id, "
                + "client_message_id ?\\) where client_message_id is not null").matcher(sql).find()).isTrue();
    }
}
