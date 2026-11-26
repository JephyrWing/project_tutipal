package com.my.tutipal_back.users.repository;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.my.tutipal_back.users.entity.User;

/**
 * Serializes withdrawal with the paths that open new records for a user (create class, join class, help request).
 * <ul>
 *   <li>Those paths take {@link #SHARE} on their own users row as the <b>first</b> lock of the transaction and stop when
 *       the account is gone or withdrawn.</li>
 *   <li>Withdrawal takes {@link #WITHDRAWAL} (FOR NO KEY UPDATE) as its first lock. It conflicts with FOR SHARE, so a
 *       running create/join/request finishes first (and withdrawal then sees and closes its rows), and a later one
 *       waits and then sees provider='withdrawn'.</li>
 *   <li>FOR NO KEY UPDATE does not conflict with FOR KEY SHARE, so ordinary foreign-key inserts (chat, answers, ...)
 *       that already hold a class row lock are not blocked by the gate: no new lock cycle with the class-row-first
 *       order. Both sides lock users row first, then class rows.</li>
 * </ul>
 */
@Repository
public class AccountLocks {

    static final String SHARE = "SELECT provider FROM users WHERE id = :u FOR SHARE";
    static final String WITHDRAWAL = "SELECT provider FROM users WHERE id = :u FOR NO KEY UPDATE";

    private final JdbcClient jdbc;

    public AccountLocks(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Locks the users row FOR SHARE until the transaction ends. False if the account is missing or withdrawn. */
    public boolean lockActive(Long userId) {
        return active(provider(SHARE, userId));
    }

    /** Withdrawal gate. False if the account is missing or already withdrawn. */
    public boolean lockForWithdrawal(Long userId) {
        return active(provider(WITHDRAWAL, userId));
    }

    private Optional<String> provider(String sql, Long userId) {
        return jdbc.sql(sql).param("u", userId).query(String.class).optional();
    }

    private static boolean active(Optional<String> provider) {
        return provider.filter(p -> !User.WITHDRAWN_PROVIDER.equals(p)).isPresent();
    }
}
