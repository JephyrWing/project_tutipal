package com.my.tutipal_back.sessions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.common.realtime.Presence;
import com.my.tutipal_back.common.realtime.SessionEvents;
import com.my.tutipal_back.sessions.dto.CreateSessionRequest;
import com.my.tutipal_back.sessions.entity.StudySession;
import com.my.tutipal_back.sessions.repository.SessionParticipantRepository;
import com.my.tutipal_back.sessions.repository.StudySessionRepository;
import com.my.tutipal_back.users.service.UserService;

/**
 * Codex P1 (PT-55): creating or joining a class first locks the caller's users row (serialized with withdrawal,
 * before any class row lock) and a withdrawn account is refused without writing anything.
 */
class StudySessionWithdrawnAccountTest {

    private static final long USER = 7L;

    private final StudySessionRepository sessions = mock(StudySessionRepository.class);
    private final SessionParticipantRepository participants = mock(SessionParticipantRepository.class);
    private final UserService users = mock(UserService.class);
    private final SessionEvents events = mock(SessionEvents.class);
    private StudySessionService service;

    @BeforeEach
    void setUp() {
        service = new StudySessionService(sessions, participants, users, events, mock(Presence.class),
                mock(ApplicationEventPublisher.class));
    }

    private void accountIsWithdrawn() {
        doThrow(new ApiException(HttpStatus.UNAUTHORIZED, "탈퇴")).when(users).lockActiveAccount(USER);
    }

    @Test
    void createLocksTheAccountBeforeSavingTheClass() {
        when(sessions.save(any(StudySession.class))).thenAnswer(call -> {
            StudySession saved = call.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 31L);
            return saved;
        });

        service.create(USER, new CreateSessionRequest("자바", "자바"));

        InOrder order = inOrder(users, sessions);
        order.verify(users).lockActiveAccount(USER);
        order.verify(sessions).save(any(StudySession.class));
    }

    @Test
    void withdrawnAccountCannotCreateAClass() {
        accountIsWithdrawn();

        assertThatThrownBy(() -> service.create(USER, new CreateSessionRequest("자바", "자바")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(sessions, participants);
    }

    @Test
    void joinLocksTheAccountBeforeTheClassRow() {
        when(sessions.findByInviteCode(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.join(USER, "ABC234")).isInstanceOf(ApiException.class);

        InOrder order = inOrder(users, sessions);
        order.verify(users).lockActiveAccount(USER);
        order.verify(sessions).findByInviteCode(anyString());   // PESSIMISTIC_WRITE on the class row
    }

    @Test
    void withdrawnAccountCannotJoin() {
        accountIsWithdrawn();

        assertThatThrownBy(() -> service.join(USER, "ABC234"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(sessions, participants, events);
    }
}
