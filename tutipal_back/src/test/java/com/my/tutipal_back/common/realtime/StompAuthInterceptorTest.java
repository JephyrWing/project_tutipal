package com.my.tutipal_back.common.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.Principal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import com.my.tutipal_back.sessions.entity.SessionRole;
import com.my.tutipal_back.sessions.service.StudySessionService;

/**
 * PT-25 NFR-SEC-05: STOMP 는 화면에서 서버로 보내는 SEND 를 모두 막고, 세션 토픽 구독은 참가자만,
 * teacher 토픽은 그 세션 선생님만 허용한다.
 */
class StompAuthInterceptorTest {

    private static final Principal TEACHER = () -> "1";
    private static final Principal STUDENT = () -> "2";
    private static final Principal OUTSIDER = () -> "3";

    private StompAuthInterceptor interceptor;
    private final MessageChannel channel = mock(MessageChannel.class);

    @BeforeEach
    void setUp() {
        StudySessionService sessions = mock(StudySessionService.class);
        when(sessions.roleOf(1L, 10L)).thenReturn(Optional.of(SessionRole.TEACHER));
        when(sessions.roleOf(2L, 10L)).thenReturn(Optional.of(SessionRole.STUDENT));
        when(sessions.roleOf(3L, 10L)).thenReturn(Optional.empty());
        interceptor = new StompAuthInterceptor(sessions);
    }

    @Test
    void sendIsRejectedEvenForTeacherOnOwnSessionTopic() {
        Message<byte[]> send = frame(StompCommand.SEND, TEACHER, "/topic/sessions/10/events");

        assertThatThrownBy(() -> interceptor.preSend(send, channel)).isInstanceOf(MessagingException.class);
    }

    @Test
    void participantsCanSubscribeToSessionEvents() {
        assertAllowed(TEACHER, "/topic/sessions/10/events");
        assertAllowed(STUDENT, "/topic/sessions/10/events");
    }

    @Test
    void onlyTeacherCanSubscribeToTeacherTopic() {
        assertAllowed(TEACHER, "/topic/sessions/10/teacher");
        assertRejected(STUDENT, "/topic/sessions/10/teacher");
    }

    @Test
    void outsiderCannotSubscribeToAnySessionTopic() {
        assertRejected(OUTSIDER, "/topic/sessions/10/events");
        assertRejected(OUTSIDER, "/topic/sessions/10/teacher");
    }

    @Test
    void subscriptionWithoutLoginIsRejected() {
        assertRejected(null, "/topic/sessions/10/events");
        assertRejected(null, "/user/queue/events");
    }

    @Test
    void personalQueuesAreAllowedForAnyLoggedInUser() {
        assertAllowed(OUTSIDER, "/user/queue/events");
        assertAllowed(OUTSIDER, "/user/queue/draw");
    }

    @Test
    void unknownOrMalformedDestinationsAreRejected() {
        assertRejected(TEACHER, "/topic/sessions/10/other");
        assertRejected(TEACHER, "/topic/sessions/abc/events");
        assertRejected(TEACHER, "/topic/sessions/10/events/extra");
        assertRejected(TEACHER, "/user/queue/other");
        assertRejected(TEACHER, "/topic/broadcast");
        assertRejected(TEACHER, null);
    }

    @Test
    void connectAndOtherFramesPassThrough() {
        Message<byte[]> connect = frame(StompCommand.CONNECT, TEACHER, null);

        assertThat(interceptor.preSend(connect, channel)).isSameAs(connect);
    }

    private void assertAllowed(Principal user, String destination) {
        Message<byte[]> subscribe = frame(StompCommand.SUBSCRIBE, user, destination);
        assertThatCode(() -> interceptor.preSend(subscribe, channel)).doesNotThrowAnyException();
    }

    private void assertRejected(Principal user, String destination) {
        Message<byte[]> subscribe = frame(StompCommand.SUBSCRIBE, user, destination);
        assertThatThrownBy(() -> interceptor.preSend(subscribe, channel)).isInstanceOf(MessagingException.class);
    }

    private static Message<byte[]> frame(StompCommand command, Principal user, String destination) {
        StompHeaderAccessor h = StompHeaderAccessor.create(command);
        h.setUser(user);
        if (destination != null) {
            h.setDestination(destination);
        }
        h.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], h.getMessageHeaders());
    }
}
