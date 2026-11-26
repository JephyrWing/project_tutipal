package com.my.tutipal_back.users.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.my.tutipal_back.common.auth.service.ActiveSessions;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.common.realtime.SessionEvents;
import com.my.tutipal_back.materials.service.MaterialObjectStore;
import com.my.tutipal_back.users.repository.AccountCleanup;
import com.my.tutipal_back.users.repository.UserRepository;

class WithdrawalServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final AccountCleanup cleanup = mock(AccountCleanup.class);
    private final SessionEvents events = mock(SessionEvents.class);
    private final MaterialObjectStore objects = mock(MaterialObjectStore.class);
    private final ActiveSessions sessions = mock(ActiveSessions.class);
    private final WithdrawalService service = new WithdrawalService(users, cleanup, events, objects, sessions);

    @Test
    void endsEveryLoginSessionOfTheUserOnlyAfterCommit() {
        when(users.existsById(7L)).thenReturn(true);
        when(cleanup.materialKeys(7L)).thenReturn(List.of("k1"));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.withdraw(7L);
            verify(sessions, never()).invalidateUser(7L); // 롤백되면 로그인은 남아야 한다
            verify(objects, never()).delete("k1");

            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        InOrder order = inOrder(cleanup, sessions, objects);
        order.verify(cleanup).deleteAll(7L);
        order.verify(sessions).invalidateUser(7L);
        order.verify(objects).delete("k1");
    }

    @Test
    void unknownUserTouchesNoSession() {
        when(users.existsById(9L)).thenReturn(false);

        assertThatThrownBy(() -> service.withdraw(9L)).isInstanceOf(ApiException.class);
        verify(sessions, never()).invalidateUser(9L);
    }
}
