package com.my.tutipal_back.common.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;

import com.my.tutipal_back.sessions.entity.SessionRole;
import com.my.tutipal_back.sessions.service.StudySessionService;

class SessionEventAccessInterceptorTest {

    private final StudySessionService sessions = mock(StudySessionService.class);
    private final SessionWebSocketGuard guard = mock(SessionWebSocketGuard.class);
    private final SessionEventAccessInterceptor interceptor = new SessionEventAccessInterceptor(sessions, guard);
    private final MessageChannel channel = mock(MessageChannel.class);
    private final MessageHandler handler = mock(MessageHandler.class);

    @Test
    void stopsDeliveringClassEventsToAnExistingSubscriptionAfterRemoval() {
        Message<?> event = event("/topic/sessions/10/events", "connected-student");
        when(guard.subscriber("connected-student")).thenReturn(() -> "2");
        when(sessions.participantRole(2L, 10L)).thenReturn(Optional.of(SessionRole.STUDENT));

        assertThat(interceptor.beforeHandle(event, channel, handler)).isSameAs(event);

        when(sessions.participantRole(2L, 10L)).thenReturn(Optional.empty());
        assertThat(interceptor.beforeHandle(event, channel, handler)).isNull();
    }

    @Test
    void teacherTopicRequiresCurrentTeacherRole() {
        Message<?> event = event("/topic/sessions/10/teacher", "connected-user");
        when(guard.subscriber("connected-user")).thenReturn(() -> "2");
        when(sessions.participantRole(2L, 10L)).thenReturn(Optional.of(SessionRole.TEACHER));

        assertThat(interceptor.beforeHandle(event, channel, handler)).isSameAs(event);

        when(sessions.participantRole(2L, 10L)).thenReturn(Optional.of(SessionRole.STUDENT));
        assertThat(interceptor.beforeHandle(event, channel, handler)).isNull();
    }

    @Test
    void dropsClassEventsWithoutAnAuthenticatedRecipient() {
        Message<?> event = event("/topic/sessions/10/events", "disconnected-user");

        assertThat(interceptor.beforeHandle(event, channel, handler)).isNull();
        verifyNoInteractions(sessions);
    }

    // PT-100: 개인 큐(/user/queue/events, /user/queue/draw)도 로그인 세션이 살아 있는 연결에만 전달한다.
    // (이전에는 guard 를 거치지 않았다 — 계획된 변경이며 약화가 아니다.)
    @Test
    void deliversPrivateNotificationsAndDrawingsToValidSubscriber() {
        Message<?> notification = event("/queue/events-user2", "connected-student");
        Message<?> drawing = event("/queue/draw-user2", "connected-student");
        when(guard.subscriber("connected-student")).thenReturn(() -> "2");

        assertThat(interceptor.beforeHandle(notification, channel, handler)).isSameAs(notification);
        assertThat(interceptor.beforeHandle(drawing, channel, handler)).isSameAs(drawing);
        verifyNoInteractions(sessions);
    }

    @Test
    void dropsPrivateNotificationsAndDrawingsWhenLoginSessionIsGone() {
        Message<?> notification = event("/queue/events-user2", "logged-out-student");
        Message<?> drawing = event("/queue/draw-user2", "logged-out-student");
        when(guard.subscriber("logged-out-student")).thenReturn(null);

        assertThat(interceptor.beforeHandle(notification, channel, handler)).isNull();
        assertThat(interceptor.beforeHandle(drawing, channel, handler)).isNull();
        verifyNoInteractions(sessions);
    }

    private static Message<?> event(String destination, String socketId) {
        var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setDestination(destination);
        headers.setSessionId(socketId);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }
}
