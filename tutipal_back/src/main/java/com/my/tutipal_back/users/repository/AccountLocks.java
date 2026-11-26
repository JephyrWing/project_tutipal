package com.my.tutipal_back.users.repository;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.my.tutipal_back.users.entity.User;

/**
 * Users row access that must be serialized with withdrawal: the paths that open new records (create class, join class,
 * help request), renaming, and the login re-check.
 * <ul>
 *   <li>Those paths take {@link #SHARE} on their own users row as the <b>first</b> lock of the transaction and stop when
 *       the account is gone or withdrawn.</li>
 *   <li>Withdrawal takes {@link #WITHDRAWAL} (FOR NO KEY UPDATE) as its first lock. It conflicts with FOR SHARE, so a
 *       running create/join/request finishes first (and withdrawal then sees and closes its rows), and a later one
 *       waits and then sees provider='withdrawn'.</li>
 *   <li>FOR NO KEY UPDATE does not conflict with FOR KEY SHARE, so ordinary foreign-key inserts (chat, answers, ...)
 *       that already hold a class row lock are not blocked by the gate: no new lock cycle with the class-row-first
 *       order. Both sides lock users row first, then class rows.</li>
 *   <li>Renaming is one targeted {@link #RENAME} (name and updated_at only, never a full-row write that could bring back
 *       the old provider / provider_id). Its row lock waits for a running withdrawal, and PostgreSQL then re-checks
 *       {@code provider <> 'withdrawn'}, so it updates 0 rows. A single statement also avoids the FOR SHARE → UPDATE
 *       upgrade deadlock two concurrent renames of the same user would have.</li>
 *   <li>{@link #isActive} is a plain read (no lock) for the login re-check inside ActiveSessions.exclusiveForUser.</li>
 * </ul>
 */
@Repository
public class AccountLocks {

    static final String SHARE = "SELECT provider FROM users WHERE id = :u FOR SHARE";
    static final String WITHDRAWAL = "SELECT provider FROM users WHERE id = :u FOR NO KEY UPDATE";
    static final String CURRENT = "SELECT provider FROM users WHERE id = :u";
    static final String RENAME = "UPDATE users SET name = :name, updated_at = CURRENT_TIMESTAMP"
            + " WHERE id = :u AND provider <> :withdrawn";

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

    /** Current committed state, no lock. False if the account is missing or withdrawn. */
    public boolean isActive(Long userId) {
        return active(provider(CURRENT, userId));
    }

    /** Sets only the display name. 0 = the account is missing or withdrawn (also when a withdrawal won the race). */
    public int renameIfActive(Long userId, String name) {
        return jdbc.sql(RENAME)
                .param("name", name)
                .param("withdrawn", User.WITHDRAWN_PROVIDER)
                .param("u", userId)
                .update();
    }

    private Optional<String> provider(String sql, Long userId) {
        return jdbc.sql(sql).param("u", userId).query(String.class).optional();
    }

    private static boolean active(Optional<String> provider) {
        return provider.filter(p -> !User.WITHDRAWN_PROVIDER.equals(p)).isPresent();
    }
}
