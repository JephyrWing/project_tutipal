package com.my.tutipal_back.sessions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.my.tutipal_back.sessions.dto.UpdateSettingsRequest;
import com.my.tutipal_back.sessions.entity.SessionParticipant;
import com.my.tutipal_back.sessions.entity.SessionRole;
import com.my.tutipal_back.sessions.entity.StudySession;
import com.my.tutipal_back.sessions.repository.SessionParticipantRepository;
import com.my.tutipal_back.sessions.repository.StudySessionRepository;
import com.my.tutipal_back.users.service.UserService;

/**
 * PT-25 NFR-SEC-03: 세션 권한은 계정 역할이 아니라 "이 세션의 참가자/선생님인가" 로 서버가 정한다.
 * 참가자가 아니면 404 (세션이 있는지도 알리지 않음), 학생이 선생님 기능을 쓰면 403, 막힌 요청은 아무것도 바꾸지 않는다.
 */
class StudySessionServiceAuthTest {

    private static final long SESSION = 10L;
    private static final long TEACHER = 1L;
    private static final long STUDENT = 2L;
    private static final long OUTSIDER = 3L;

    private StudySessionRepository sessions;
    private SessionParticipantRepository participants;
    private SessionEvents events;
    private ApplicationEventPublisher publisher;
    private StudySessionService service;
    private StudySession session;

    @BeforeEach
    void setUp() {
        sessions = mock(StudySessionRepository.class);
        participants = mock(SessionParticipantRepository.class);
        events = mock(SessionEvents.class);
        publisher = mock(ApplicationEventPublisher.class);
        service = new StudySessionService(sessions, participants, mock(UserService.class), events,
                mock(Presence.class), publisher);

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
    void outsiderGetsNotFoundForSessionDetails() {
        assertStatus(() -> service.get(OUTSIDER, SESSION), HttpStatus.NOT_FOUND);
    }

    @Test
    void outsiderGetsNotFoundEvenForTeacherOnlyFeatures() {
        // 403 이 아니라 404: 비참가자에게 "선생님 전용" 이라고 답하면 세션이 있다는 사실이 새어 나간다
        assertStatus(() -> service.participants(OUTSIDER, SESSION), HttpStatus.NOT_FOUND);
        assertStatus(() -> service.end(OUTSIDER, SESSION), HttpStatus.NOT_FOUND);
    }

    @Test
    void studentCannotListParticipants() {
        assertStatus(() -> service.participants(STUDENT, SESSION), HttpStatus.FORBIDDEN);
    }

    @Test
    void studentCannotChangeSettingsAndNothingIsBroadcast() {
        UpdateSettingsRequest request = new UpdateSettingsRequest("파이썬", 5, false, false);

        assertStatus(() -> service.updateSettings(STUDENT, SESSION, request), HttpStatus.FORBIDDEN);

        assertThat(session.getTopic()).isEqualTo("자바");
        verify(events, never()).toSession(anyLong(), anyString(), any());
    }

    @Test
    void studentCannotEndSession() {
        assertStatus(() -> service.end(STUDENT, SESSION), HttpStatus.FORBIDDEN);

        assertThat(session.isEnded()).isFalse();
    }

    @Test
    void studentCannotKickAnotherStudent() {
        assertStatus(() -> service.kick(STUDENT, SESSION, 4L), HttpStatus.FORBIDDEN);

        verify(participants, never()).delete(any());
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void teacherCannotLeaveOwnSession() {
        assertStatus(() -> service.leave(TEACHER, SESSION), HttpStatus.FORBIDDEN);

        verify(participants, never()).delete(any());
    }

    @Test
    void teacherOnlyCheckUsedByOtherDomainsFollowsSameRules() {
        assertStatus(() -> service.requireTeacherOf(OUTSIDER, SESSION), HttpStatus.NOT_FOUND);
        assertStatus(() -> service.requireTeacherOf(STUDENT, SESSION), HttpStatus.FORBIDDEN);
        service.requireTeacherOf(TEACHER, SESSION);
    }

    @Test
    void endedSessionRejectsActiveOnlyFeaturesWithGone() {
        session.end(Instant.parse("2026-11-22T00:00:00Z"));

        assertStatus(() -> service.requireActiveRole(STUDENT, SESSION), HttpStatus.GONE);
        assertStatus(() -> service.chatSettings(STUDENT, SESSION), HttpStatus.GONE);
    }

    @Test
    void stompRoleLookupGivesRoleOnlyToParticipantsOfActiveSession() {
        assertThat(service.roleOf(TEACHER, SESSION)).contains(SessionRole.TEACHER);
        assertThat(service.roleOf(STUDENT, SESSION)).contains(SessionRole.STUDENT);
        assertThat(service.roleOf(OUTSIDER, SESSION)).isEmpty();

        session.end(Instant.parse("2026-11-22T00:00:00Z"));
        assertThat(service.roleOf(STUDENT, SESSION)).isEmpty();
    }

    private static void assertStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(status));
    }
}
