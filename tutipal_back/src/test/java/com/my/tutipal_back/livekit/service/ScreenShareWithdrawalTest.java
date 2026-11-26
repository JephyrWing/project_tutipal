package com.my.tutipal_back.livekit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.common.realtime.SessionEvents;
import com.my.tutipal_back.livekit.dto.ScreenShareDtos.CreateHelpRequest;
import com.my.tutipal_back.livekit.entity.HelpRequest;
import com.my.tutipal_back.livekit.entity.HelpRequest.Status;
import com.my.tutipal_back.livekit.repository.HelpRequestRepository;
import com.my.tutipal_back.sessions.entity.SessionRole;
import com.my.tutipal_back.sessions.service.StudySessionService;
import com.my.tutipal_back.users.service.UserService;

/**
 * Withdrawal closes the student's open help requests like leaving a class does; the rows are kept. A help request
 * locks the student's users row before the class row (Codex P1), so it cannot slip past a running withdrawal.
 */
class ScreenShareWithdrawalTest {

    private final HelpRequestRepository helps = mock(HelpRequestRepository.class);
    private final SessionEvents events = mock(SessionEvents.class);
    private final StudySessionService sessions = mock(StudySessionService.class);
    private final UserService users = mock(UserService.class);
    private final ScreenShareService service = new ScreenShareService(helps, sessions, users,
            mock(LiveKitTokens.class), events, "/livekit");

    private static HelpRequest help(Long id, Long sessionId, Status status) {
        HelpRequest help = new HelpRequest(sessionId, 7L, "막혔어요", true);
        ReflectionTestUtils.setField(help, "id", id);
        help.moveTo(status);
        return help;
    }

    @Test
    void openHelpRequestsInEverySessionAreDoneAndBothSidesAreTold() {
        HelpRequest pending = help(1L, 10L, Status.PENDING);
        HelpRequest accepted = help(2L, 20L, Status.ACCEPTED);
        when(helps.findByUserIdAndStatusInOrderByIdAsc(7L, Set.of(Status.PENDING, Status.ACCEPTED)))
                .thenReturn(List.of(pending, accepted));

        service.closeOpenHelpsOf(7L);

        assertThat(pending.getStatus()).isEqualTo(Status.DONE);
        assertThat(accepted.getStatus()).isEqualTo(Status.DONE);
        verify(events).toUser(7L, "helpEnded", 1L);
        verify(events).toTeacher(10L, "help", 1L);
        verify(events).toUser(7L, "helpEnded", 2L);
        verify(events).toTeacher(20L, "help", 2L);
    }

    @Test
    void noOpenRequestSendsNothing() {
        when(helps.findByUserIdAndStatusInOrderByIdAsc(7L, Set.of(Status.PENDING, Status.ACCEPTED)))
                .thenReturn(List.of());

        service.closeOpenHelpsOf(7L);

        verifyNoInteractions(events);
    }

    @Test
    void helpRequestLocksTheAccountBeforeTheClassRow() {
        when(sessions.requireActiveRole(7L, 10L)).thenReturn(SessionRole.STUDENT);
        when(helps.save(any(HelpRequest.class))).thenAnswer(call -> {
            HelpRequest saved = call.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 3L);
            return saved;
        });

        service.request(7L, 10L, new CreateHelpRequest("막혔어요", false));

        InOrder order = inOrder(users, sessions, helps);
        order.verify(users).lockActiveAccount(7L);
        order.verify(sessions).lockForHelp(10L);
        order.verify(helps).save(any(HelpRequest.class));
    }

    @Test
    void withdrawnStudentCannotOpenAHelpRequest() {
        doThrow(new ApiException(HttpStatus.UNAUTHORIZED, "탈퇴")).when(users).lockActiveAccount(7L);

        assertThatThrownBy(() -> service.request(7L, 10L, new CreateHelpRequest("막혔어요", false)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(sessions, helps, events);
    }
}
