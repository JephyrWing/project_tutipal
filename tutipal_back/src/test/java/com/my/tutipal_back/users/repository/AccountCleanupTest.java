package com.my.tutipal_back.users.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.jdbc.core.JdbcTemplate;

import com.my.tutipal_back.learning.focus.repository.JdbcFocusStore;

/**
 * PT-90 AC-9: account deletion removes the user's focus rows before study_sessions and users
 * (focus_states, focus_samples of the user or of sessions the user owns, focus_consents).
 */
class AccountCleanupTest {

    private static List<String> deletes() {
        return AccountCleanup.DELETES.stream().map(s -> s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ")).toList();
    }

    private static int indexOf(String table) {
        List<String> all = deletes();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).startsWith("delete from " + table + " ")) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void focusTablesAreDeleted() {
        assertThat(indexOf("focus_states")).as("focus_states delete").isNotNegative();
        assertThat(indexOf("focus_samples")).as("focus_samples delete").isNotNegative();
        assertThat(indexOf("focus_consents")).as("focus_consents delete").isNotNegative();
    }

    @Test
    void focusTablesComeBeforeSessionsAndUsers() {
        int sessions = indexOf("study_sessions");
        int users = indexOf("users");
        assertThat(sessions).isNotNegative();
        assertThat(users).isNotNegative();

        for (String table : List.of("focus_states", "focus_samples", "focus_consents")) {
            assertThat(indexOf(table)).as(table).isNotNegative().isLessThan(sessions).isLessThan(users);
        }
    }

    @Test
    void focusTablesAreAtTheFront() {
        assertThat(deletes().subList(0, 3)).allSatisfy(s -> assertThat(s).contains("focus_"));
    }

    @Test
    void samplesAndStatesOfOwnedSessionsGoToo() {
        String samples = deletes().get(indexOf("focus_samples"));
        String states = deletes().get(indexOf("focus_states"));

        assertThat(samples).contains("user_id = :u").contains("session_id in (select id from study_sessions where owner_id = :u)");
        assertThat(states).contains("user_id = :u");
    }

    @Test
    void consentsAreDeletedByUser() {
        assertThat(deletes().get(indexOf("focus_consents"))).contains("user_id = :u");
    }

    @Test
    void existingOrderIsKept() {
        List<String> tables = List.of("comments", "posts", "notices", "quiz_answers", "quizzes", "help_requests",
                "materials", "session_participants", "study_sessions", "users");
        int previous = -1;
        for (String table : tables) {
            int at = indexOf(table);
            assertThat(at).as(table).isGreaterThan(previous);
            previous = at;
        }
    }

    /** Codex round 1: one lock order for account deletion and consent withdrawal (no deadlock between them). */
    @Test
    void focusTablesAreDeletedInTheSameOrderAsConsentWithdrawal() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new JdbcFocusStore(jdbc).deleteUserData(2L);
        List<String> withdrawal = new ArrayList<>();
        for (Invocation i : mockingDetails(jdbc).getInvocations()) {
            String sql = String.valueOf(i.getRawArguments()[0]).toLowerCase(Locale.ROOT);
            for (String table : List.of("focus_consents", "focus_samples", "focus_states")) {
                if (sql.startsWith("delete from " + table + " ")) {
                    withdrawal.add(table);
                }
            }
        }

        assertThat(withdrawal).containsExactly("focus_consents", "focus_samples", "focus_states");
        assertThat(indexOf("focus_consents")).isLessThan(indexOf("focus_samples"));
        assertThat(indexOf("focus_samples")).isLessThan(indexOf("focus_states"));
    }
}
