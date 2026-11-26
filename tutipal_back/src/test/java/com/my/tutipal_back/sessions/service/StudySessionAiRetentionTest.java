package com.my.tutipal_back.sessions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.common.realtime.Presence;
import com.my.tutipal_back.common.realtime.SessionEvents;
import com.my.tutipal_back.sessions.dto.CreateSessionRequest;
import com.my.tutipal_back.sessions.dto.SessionResponse;
import com.my.tutipal_back.sessions.entity.SessionParticipant;
import com.my.tutipal_back.sessions.entity.SessionRole;
import com.my.tutipal_back.sessions.entity.StudySession;
import com.my.tutipal_back.sessions.repository.SessionParticipantRepository;
import com.my.tutipal_back.sessions.repository.StudySessionRepository;
import com.my.tutipal_back.users.service.UserService;

/**
 * PT-13 decision A: the teacher sets the class AI conversation retention (0..90 days after the session ends) when
 * creating a session, sees it in the response, and can change it later (also after the end). Teacher only:
 * outsider 404, student 403, and a refused change leaves the value as it was.
 */
class StudySessionAiRetentionTest {

    private static final long SESSION = 10L;
    private static final long TEACHER = 1L;
    private static final long STUDENT = 2L;
    private static final long OUTSIDER = 3L;

    private StudySessionRepository sessions;
    private SessionParticipantRepository participants;
    private SessionEvents events;
    private StudySessionService service;
    private StudySession session;

    @BeforeEach
    void setUp() {
        sessions = mock(StudySessionRepository.class);
        participants = mock(SessionParticipantRepository.class);
        events = mock(SessionEvents.class);
        service = new StudySessionService(sessions, participants, mock(UserService.class), events,
                mock(Presence.class), mock(ApplicationEventPublisher.class));
        session = new StudySession("자바 기초", "자바", "ABC234", TEACHER, 30);
        ReflectionTestUtils.setField(session, "id", SESSION);
        when(sessions.findById(SESSION)).thenReturn(Optional.of(session));
        when(participants.findBySessionIdAndUserId(SESSION, TEACHER))
                .thenReturn(Optional.of(new SessionParticipant(SESSION, TEACHER, SessionRole.TEACHER)));
        when(participants.findBySessionIdAndUserId(SESSION, STUDENT))
                .thenReturn(Optional.of(new SessionParticipant(SESSION, STUDENT, SessionRole.STUDENT)));
        when(participants.findBySessionIdAndUserId(SESSION, OUTSIDER)).thenReturn(Optional.empty());
    }

    @Test
    void createStoresTheChosenRetentionAndReturnsIt() {
        when(sessions.save(any(StudySession.class))).thenAnswer(inv -> {
            StudySession saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 77L);
            return saved;
        });

        SessionResponse created = service.create(TEACHER, new CreateSessionRequest("3주차", "자바", 0));

        assertThat(created.aiRetentionDays()).isZero();
        verify(sessions).save(argThat(s -> s.getAiRetentionDays() == 0));
    }

    @Test
    void responseCarriesTheRetentionForStudentsToo() {
        assertThat(service.get(STUDENT, SESSION).aiRetentionDays()).isEqualTo(30);
    }

    @Test
    void ownerTeacherChangesTheRetention() {
        SessionResponse updated = service.updateAiRetention(TEACHER, SESSION, 90);

        assertThat(updated.aiRetentionDays()).isEqualTo(90);
        assertThat(session.getAiRetentionDays()).isEqualTo(90);
    }

    /** Retention counts from the end, so the teacher can still adjust it after closing the session. */
    @Test
    void retentionCanBeChangedAfterTheSessionEnded() {
        session.end(Instant.parse("2026-11-20T00:00:00Z"));

        assertThat(service.updateAiRetention(TEACHER, SESSION, 7).aiRetentionDays()).isEqualTo(7);
    }

    @Test
    void studentCannotChangeTheRetention() {
        assertStatus(() -> service.updateAiRetention(STUDENT, SESSION, 0), HttpStatus.FORBIDDEN);

        assertThat(session.getAiRetentionDays()).isEqualTo(30);
        verify(events, never()).toSession(anyLong(), anyString(), any());
    }

    @Test
    void outsiderGetsNotFound() {
        assertStatus(() -> service.updateAiRetention(OUTSIDER, SESSION, 0), HttpStatus.NOT_FOUND);

        assertThat(session.getAiRetentionDays()).isEqualTo(30);
    }

    @Test
    void entityRejectsValuesOutsideZeroToNinety() {
        assertThatThrownBy(() -> session.changeAiRetentionDays(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> session.changeAiRetentionDays(91)).isInstanceOf(IllegalArgumentException.class);
        assertThat(session.getAiRetentionDays()).isEqualTo(30);
    }

    private static void assertStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(status));
    }
}
