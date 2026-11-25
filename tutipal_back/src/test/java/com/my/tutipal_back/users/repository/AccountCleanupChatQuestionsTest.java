package com.my.tutipal_back.users.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * PT-87 AC-7 / D6: account deletion removes chat_questions explicitly (the user's own rows and every row of
 * sessions the user owns), before quizzes and before the session / user rows in foreign-key order.
 * The real-database effect is checked in ConceptInsightsPostgresTest.
 */
class AccountCleanupChatQuestionsTest {

    private List<String> statements() {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        new AccountCleanup(jdbc).deleteAll(5L);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).sql(sql.capture());
        return sql.getAllValues();
    }

    private static int indexOf(List<String> statements, String prefix) {
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void deletesChatQuestionsOfTheUserAndOfOwnedSessions() {
        List<String> statements = statements();

        int questions = indexOf(statements, "DELETE FROM chat_questions");
        assertThat(questions).as("explicit chat_questions delete").isNotNegative();
        String sql = statements.get(questions);
        assertThat(sql).contains("user_id = :u");
        assertThat(sql).contains("owner_id = :u");
    }

    @Test
    void chatQuestionsAreDeletedBeforeQuizzesSessionsAndUsers() {
        List<String> statements = statements();

        int questions = indexOf(statements, "DELETE FROM chat_questions");
        assertThat(questions).isNotNegative();
        assertThat(questions).isLessThan(indexOf(statements, "DELETE FROM quizzes"));
        assertThat(questions).isLessThan(indexOf(statements, "DELETE FROM study_sessions"));
        assertThat(questions).isLessThan(indexOf(statements, "DELETE FROM users"));
    }
}
