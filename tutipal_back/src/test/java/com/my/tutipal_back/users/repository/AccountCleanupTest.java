package com.my.tutipal_back.users.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.my.tutipal_back.users.entity.User;

/**
 * PM 2026-11-26 (PT-13): withdrawal anonymizes the users row and deletes nothing. Replaces the old deletion-order
 * checks (PT-90 AC-9 focus rows, PT-87 AC-7 chat_questions): those rows are now kept with the anonymized account.
 * The real-database effect is checked in FocusStorePostgresTest and ConceptInsightsPostgresTest.
 */
class AccountCleanupTest {

    private static String normalized(String sql) {
        return sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** Every SQL statement a full withdrawal sends through AccountCleanup, in call order. */
    private static List<String> statementsOfAWithdrawal() {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        AccountCleanup cleanup = new AccountCleanup(jdbc);
        cleanup.lockHelpSessions(5L);
        cleanup.openOwnedSessionIds(5L);
        cleanup.anonymize(5L);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).sql(sql.capture());
        return sql.getAllValues().stream().map(AccountCleanupTest::normalized).toList();
    }

    @Test
    void withdrawalDeletesNoRecord() {
        assertThat(statementsOfAWithdrawal()).hasSize(3).noneMatch(s -> s.contains("delete"));
    }

    @Test
    void onlyTheUsersRowIsUpdated() {
        assertThat(statementsOfAWithdrawal()).filteredOn(s -> s.startsWith("update"))
                .singleElement().satisfies(s -> assertThat(s).startsWith("update users set "));
    }

    @Test
    void anonymizeRemovesNameEmailAndSocialIdAndResetsRole() {
        String sql = normalized(AccountCleanup.ANONYMIZE);

        assertThat(sql).contains("provider = :withdrawn")
                .contains("provider_id = cast(id as varchar(100))")   // keeps uk_users_provider unique per user
                .contains("name = :name")
                .contains("email = null")
                .contains("role = 'user'")
                .contains("where id = :u");
    }

    @Test
    void alreadyWithdrawnRowIsNotUpdatedAgain() {
        assertThat(normalized(AccountCleanup.ANONYMIZE)).contains("and provider <> :withdrawn");
    }

    @Test
    void anonymizeBindsTheWithdrawnProviderAndDisplayName() {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        JdbcClient.StatementSpec spec = jdbc.sql(AccountCleanup.ANONYMIZE);

        new AccountCleanup(jdbc).anonymize(5L);

        verify(spec).param("withdrawn", User.WITHDRAWN_PROVIDER);
        assertThat(User.WITHDRAWN_PROVIDER).isEqualTo("withdrawn");
        assertThat(User.WITHDRAWN_NAME).isEqualTo("탈퇴한 사용자");
    }

    @Test
    void sessionsAreLockedInIdOrder() {
        String lock = statementsOfAWithdrawal().getFirst();

        assertThat(lock).contains("owner_id = :u").contains("session_participants").endsWith("order by id for update");
    }

    @Test
    void openOwnedSessionsAreThoseNotEndedYet() {
        String open = statementsOfAWithdrawal().get(1);

        assertThat(open).contains("owner_id = :u").contains("ended_at is null");
    }
}
