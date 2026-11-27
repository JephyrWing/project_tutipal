package com.my.tutipal_back.livekit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.common.realtime.SessionEvents;
import com.my.tutipal_back.livekit.entity.HelpRequest;
import com.my.tutipal_back.livekit.entity.HelpRequest.Status;
import com.my.tutipal_back.livekit.repository.HelpRequestRepository;
import com.my.tutipal_back.sessions.service.StudySessionService;
import com.my.tutipal_back.users.service.UserService;

class ScreenShareTransitionsTest {

    private final HelpRequestRepository helps = mock(HelpRequestRepository.class);
    private final StudySessionService sessions = mock(StudySessionService.class);
    private final SessionEvents events = mock(SessionEvents.class);
    private final ScreenShareService service = new ScreenShareService(helps, sessions, mock(UserService.class),
            mock(LiveKitTokens.class), events, "/livekit");

    @ParameterizedTest
    @CsvSource({
        "accept, 1, PENDING",
        "later, 1, PENDING",
        "end, 1, ACCEPTED",
        "end, 2, ACCEPTED",
        "end, 2, PENDING"
    })
    void commandsKeepTheOriginalRequestAndSendNoEventAfterClassClosure(String action, long userId, Status status) {
        long helpId = 3L;
        long sessionId = 4L;
        HelpRequest help = new HelpRequest(sessionId, 2L, "synthetic question", false);
        help.moveTo(status);
        when(helps.sessionIdOf(helpId)).thenReturn(Optional.of(sessionId));
        when(helps.lockById(helpId)).thenReturn(Optional.of(help));
        doThrow(new ApiException(HttpStatus.GONE, "closed")).when(sessions).requireActiveRole(userId, sessionId);

        assertThatThrownBy(() -> {
            switch (action) {
                case "accept" -> service.accept(userId, helpId);
                case "later" -> service.later(userId, helpId);
                case "end" -> service.end(userId, helpId);
                default -> throw new AssertionError(action);
            }
        }).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.GONE));

        InOrder order = inOrder(sessions, helps);
        order.verify(sessions).lockForHelp(sessionId);
        order.verify(helps).lockById(helpId);
        order.verify(sessions).requireActiveRole(userId, sessionId);
        assertThat(help.getStatus()).isEqualTo(status);
        verifyNoInteractions(events);
    }
}
