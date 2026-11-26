package com.my.tutipal_back.users.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.my.tutipal_back.users.entity.User;

/**
 * Codex P1 (PT-55): the users row gate. Create / join / help request take FOR SHARE, withdrawal takes
 * FOR NO KEY UPDATE (conflicts with FOR SHARE, not with the FOR KEY SHARE of ordinary foreign-key inserts).
 * Lock behaviour on a real database: FocusStorePostgresTest.
 */
class AccountLocksTest {

    private static AccountLocks locksReturning(String sql, Optional<String> provider) {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        when(jdbc.sql(sql).param(anyString(), anyLong()).query(String.class).optional()).thenReturn(provider);
        return new AccountLocks(jdbc);
    }

    @Test
    void creatingPathsLockTheUsersRowForShare() {
        assertThat(AccountLocks.SHARE).isEqualTo("SELECT provider FROM users WHERE id = :u FOR SHARE");
    }

    @Test
    void withdrawalLocksTheUsersRowForNoKeyUpdate() {
        assertThat(AccountLocks.WITHDRAWAL).isEqualTo("SELECT provider FROM users WHERE id = :u FOR NO KEY UPDATE");
    }

    @Test
    void socialAccountIsActive() {
        assertThat(locksReturning(AccountLocks.SHARE, Optional.of("kakao")).lockActive(7L)).isTrue();
        assertThat(locksReturning(AccountLocks.WITHDRAWAL, Optional.of("google")).lockForWithdrawal(7L)).isTrue();
    }

    @Test
    void withdrawnAccountIsNotActive() {
        assertThat(locksReturning(AccountLocks.SHARE, Optional.of(User.WITHDRAWN_PROVIDER)).lockActive(7L)).isFalse();
        assertThat(locksReturning(AccountLocks.WITHDRAWAL, Optional.of(User.WITHDRAWN_PROVIDER)).lockForWithdrawal(7L))
                .isFalse();
    }

    @Test
    void missingAccountIsNotActive() {
        assertThat(locksReturning(AccountLocks.SHARE, Optional.empty()).lockActive(7L)).isFalse();
        assertThat(locksReturning(AccountLocks.WITHDRAWAL, Optional.empty()).lockForWithdrawal(7L)).isFalse();
    }
}
