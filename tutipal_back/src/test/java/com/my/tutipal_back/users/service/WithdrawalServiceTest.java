package com.my.tutipal_back.users.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.my.tutipal_back.common.auth.service.ActiveSessions;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.livekit.service.ScreenShareService;
import com.my.tutipal_back.sessions.service.StudySessionService;
import com.my.tutipal_back.users.entity.User;
import com.my.tutipal_back.users.repository.AccountCleanup;
import com.my.tutipal_back.users.repository.UserRepository;

/** FR-MY-02 / PM 2026-11-26 (PT-13): withdrawal anonymizes the account and keeps every record. */
class WithdrawalServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final AccountCleanup cleanup = mock(AccountCleanup.class);
    private final StudySessionService studySessions = mock(StudySessionService.class);
    private final ScreenShareService helps = mock(ScreenShareService.class);
    private final ActiveSessions sessions = mock(ActiveSessions.class);
    private final WithdrawalService service = new WithdrawalService(users, cleanup, studySessions, helps, sessions);

    private static User user(Long id, String provider) {
        User user = new User(provider, "social-" + id, "가입 이름");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

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
        when(users.findById(7L)).thenReturn(Optional.of(user(7L, "kakao")));
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
        order.verify(sessions).invalidateUser(7L);
    }

    @Test
    void anonymizesAfterLockingSessionsClosingOwnedClassesAndOpenHelpRequests() {
        when(users.findById(7L)).thenReturn(Optional.of(user(7L, "google")));
        when(cleanup.openOwnedSessionIds(7L)).thenReturn(List.of(31L, 32L));
        when(cleanup.anonymize(7L)).thenReturn(1);

        withdrawAndCommit(7L);

        InOrder order = inOrder(cleanup, studySessions, helps);
        order.verify(cleanup).lockHelpSessions(7L);   // session rows first, users row last (same order as chat / help)
        order.verify(studySessions).end(7L, 31L);     // same close as the teacher's "end class": ended_at + "ended"
        order.verify(studySessions).end(7L, 32L);
        order.verify(helps).closeOpenHelpsOf(7L);
        order.verify(cleanup).anonymize(7L);
    }

    @Test
    void unknownUserIs404AndTouchesNothing() {
        when(users.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.withdraw(9L))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(cleanup, never()).anonymize(anyLong());
        verifyNoInteractions(studySessions, helps, sessions);
    }

    @Test
    void secondWithdrawalOfAnAnonymizedUserIs404LikeAMissingUser() {
        when(users.findById(7L)).thenReturn(Optional.of(user(7L, User.WITHDRAWN_PROVIDER)));

        assertThatThrownBy(() -> service.withdraw(7L))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(cleanup, never()).lockHelpSessions(any());
        verify(cleanup, never()).anonymize(anyLong());
        verifyNoInteractions(studySessions, helps, sessions);
    }

    @Test
    void concurrentSecondWithdrawalThatUpdatesNoRowIs404AndDoesNotEndLogins() {
        when(users.findById(7L)).thenReturn(Optional.of(user(7L, "naver")));
        when(cleanup.anonymize(7L)).thenReturn(0);   // the other request anonymized it first

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
        when(users.findById(7L)).thenReturn(Optional.of(user(7L, "kakao")));
        when(cleanup.openOwnedSessionIds(7L)).thenReturn(List.of());
        when(cleanup.anonymize(7L)).thenReturn(1);

        withdrawAndCommit(7L);

        verify(studySessions, never()).end(anyLong(), anyLong());
        verify(helps).closeOpenHelpsOf(7L);
    }
}
