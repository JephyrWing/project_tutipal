package com.my.tutipal_back.users.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.my.tutipal_back.common.auth.service.ActiveSessions;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.livekit.service.ScreenShareService;
import com.my.tutipal_back.sessions.service.StudySessionService;
import com.my.tutipal_back.users.repository.AccountCleanup;
import com.my.tutipal_back.users.repository.AccountLocks;

/** FR-MY-02 / PM 2026-11-26 (PT-13): withdrawal anonymizes the account and keeps every record. */
class WithdrawalServiceTest {

    private final AccountLocks locks = mock(AccountLocks.class);
    private final AccountCleanup cleanup = mock(AccountCleanup.class);
    private final StudySessionService studySessions = mock(StudySessionService.class);
    private final ScreenShareService helps = mock(ScreenShareService.class);
    private final ActiveSessions sessions = spy(new ActiveSessions());   // real per-account lock
    private final WithdrawalService service = new WithdrawalService(locks, cleanup, studySessions, helps, sessions);

    private void withdrawAndCommit(Long userId) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.withdraw(userId);
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void endsEveryLoginSessionOfTheUserOnlyAfterCommit() {
        when(locks.lockForWithdrawal(7L)).thenReturn(true);
        when(cleanup.anonymize(7L)).thenReturn(1);

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.withdraw(7L);
            verify(sessions, never()).invalidateUser(7L); // 롤백되면 로그인은 남아야 한다

            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        InOrder order = inOrder(cleanup, sessions);
        order.verify(cleanup).anonymize(7L);
        order.verify(sessions).exclusiveForUser(eq(7L), any());
        order.verify(sessions).invalidateUser(7L);
    }

    /** Codex P1-2: invalidation after commit takes the same per-account lock as SessionLogin. */
    @Test
    void invalidationAfterCommitWaitsForTheUsersRunningLogin() throws Exception {
        when(locks.lockForWithdrawal(7L)).thenReturn(true);
        when(cleanup.anonymize(7L)).thenReturn(1);
        CountDownLatch loginHolding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> login = pool.submit(() -> sessions.exclusiveForUser(7L, () -> {
                loginHolding.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(loginHolding.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> withdrawal = pool.submit(() -> withdrawAndCommit(7L));
            Thread.sleep(200);
            verify(sessions, never()).invalidateUser(7L);
            assertThat(withdrawal.isDone()).as("invalidation waits for the login in progress").isFalse();
            release.countDown();
            login.get(5, TimeUnit.SECONDS);
            withdrawal.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        verify(sessions).invalidateUser(7L);
    }

    @Test
    void anonymizesAfterTheAccountGateLockingClassesClosingOwnedClassesAndOpenHelpRequests() {
        when(locks.lockForWithdrawal(7L)).thenReturn(true);
        when(cleanup.openOwnedSessionIds(7L)).thenReturn(List.of(31L, 32L));
        when(cleanup.anonymize(7L)).thenReturn(1);

        withdrawAndCommit(7L);

        InOrder order = inOrder(locks, cleanup, studySessions, helps);
        order.verify(locks).lockForWithdrawal(7L);   // users row gate first (create / join / help request use it too)
        order.verify(cleanup).lockHelpSessions(7L);   // then class rows in id order (same order as chat / help)
        order.verify(studySessions).end(7L, 31L);     // same close as the teacher's "end class": ended_at + "ended"
        order.verify(studySessions).end(7L, 32L);
        order.verify(helps).closeOpenHelpsOf(7L);
        order.verify(cleanup).anonymize(7L);
    }

    @Test
    void unknownUserIs404AndTouchesNothing() {
        when(locks.lockForWithdrawal(9L)).thenReturn(false);

        assertThatThrownBy(() -> service.withdraw(9L))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(cleanup, never()).anonymize(anyLong());
        verifyNoInteractions(studySessions, helps, sessions);
    }

    /**
     * Over HTTP the same client's second DELETE is 401 (its login session ended after the first commit). 404 is for a
     * request that raced the first one: it waits on the users row gate and then finds the account already withdrawn.
     */
    @Test
    void racingSecondWithdrawalFindsTheAccountWithdrawnAtTheGateAndIs404() {
        when(locks.lockForWithdrawal(7L)).thenReturn(false);

        assertThatThrownBy(() -> service.withdraw(7L))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(cleanup, never()).lockHelpSessions(any());
        verify(cleanup, never()).anonymize(anyLong());
        verifyNoInteractions(studySessions, helps, sessions);
    }

    @Test
    void anonymizeUpdatingNoRowIs404AndDoesNotEndLogins() {
        when(locks.lockForWithdrawal(7L)).thenReturn(true);
        when(cleanup.anonymize(7L)).thenReturn(0);   // safety net; the gate should make this impossible

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThatThrownBy(() -> service.withdraw(7L))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        verify(sessions, never()).invalidateUser(7L);
    }

    @Test
    void userWithoutOpenClassesEndsNoSession() {
        when(locks.lockForWithdrawal(7L)).thenReturn(true);
        when(cleanup.openOwnedSessionIds(7L)).thenReturn(List.of());
        when(cleanup.anonymize(7L)).thenReturn(1);

        withdrawAndCommit(7L);

        verify(studySessions, never()).end(anyLong(), anyLong());
        verify(helps).closeOpenHelpsOf(7L);
    }
}
