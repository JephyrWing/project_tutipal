package com.my.tutipal_back.livekit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.my.tutipal_back.common.realtime.SessionEvents;
import com.my.tutipal_back.livekit.entity.HelpRequest;
import com.my.tutipal_back.livekit.entity.HelpRequest.Status;
import com.my.tutipal_back.livekit.repository.HelpRequestRepository;
import com.my.tutipal_back.sessions.service.StudySessionService;
import com.my.tutipal_back.users.service.UserService;

/** Withdrawal closes the student's open help requests like leaving a class does; the rows are kept. */
class ScreenShareWithdrawalTest {

    private final HelpRequestRepository helps = mock(HelpRequestRepository.class);
    private final SessionEvents events = mock(SessionEvents.class);
    private final ScreenShareService service = new ScreenShareService(helps, mock(StudySessionService.class),
            mock(UserService.class), mock(LiveKitTokens.class), events, "/livekit");

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
}
