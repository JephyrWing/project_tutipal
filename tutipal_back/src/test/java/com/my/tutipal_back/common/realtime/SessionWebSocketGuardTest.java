package com.my.tutipal_back.common.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

/**
 * PT-100: 로그아웃·유휴 만료·절대 만료(app.session.absolute-timeout) 뒤에는 이미 열린 STOMP 연결도 권한을 잃는다.
 * subscriber() 가 null 을 돌려주고, 다음 메시지에서 연결을 POLICY_VIOLATION 으로 닫는다.
 */
class SessionWebSocketGuardTest {

    private static final Duration ABSOLUTE = Duration.ofHours(1);
    private static final int IDLE_SECONDS = 30 * 60;

    private final MutableClock clock = new MutableClock();
    private SessionWebSocketGuard guard;

    @AfterEach
    void tearDown() {
        if (guard != null) {
            guard.stop();
        }
    }

    // ---- 핸드셰이크 ----

    @Test
    void handshakeWithoutHttpSessionIsRejected() throws Exception {
        guard = new SessionWebSocketGuard(clock, ABSOLUTE);
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = guard.beforeHandshake(new ServletServerHttpRequest(new MockHttpServletRequest()),
                new ServletServerHttpResponse(new MockHttpServletResponse()), mock(WebSocketHandler.class), attributes);

        assertThat(accepted).isFalse();
        assertThat(attributes).doesNotContainKey("httpSession");
    }

    @Test
    void handshakeWithHttpSessionIsAcceptedAndRemembersTheSession() throws Exception {
        guard = new SessionWebSocketGuard(clock, ABSOLUTE);
        MockHttpSession session = loggedIn("2");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = guard.beforeHandshake(new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(new MockHttpServletResponse()), mock(WebSocketHandler.class), attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes).containsEntry("httpSession", session).containsEntry("httpSessionId", session.getId());
    }

    // ---- 유효한 연결 ----

    @Test
    void validSessionKeepsSubscriberAndPassesMessages() throws Exception {
        guard = new SessionWebSocketGuard(clock, ABSOLUTE);
        MockHttpSession session = loggedIn("2");
        clock.set(session.getCreationTime());
        WebSocketHandler delegate = mock(WebSocketHandler.class);
        WebSocketSession socket = connect(session, delegate);

        Principal subscriber = guard.subscriber("socket-1");
        assertThat(subscriber).isNotNull();
        assertThat(subscriber.getName()).isEqualTo("2");

        TextMessage message = new TextMessage("x");
        guard.decorate(delegate).handleMessage(socket, message);
        verify(delegate).handleMessage(socket, message);
        verify(socket, never()).close(any(CloseStatus.class));
    }

    @Test
    void unknownSocketHasNoSubscriber() {
        guard = new SessionWebSocketGuard(clock, ABSOLUTE);

        assertThat(guard.subscriber("missing")).isNull();
        assertThat(guard.subscriber(null)).isNull();
    }

    // ---- 로그아웃 ----

    @Test
    void afterLogoutSubscriberIsGoneAndSocketIsClosed() throws Exception {
        guard = new SessionWebSocketGuard(clock, ABSOLUTE);
        MockHttpSession session = loggedIn("2");
        clock.set(session.getCreationTime());
        WebSocketHandler delegate = mock(WebSocketHandler.class);
        WebSocketSession socket = connect(session, delegate);

        session.invalidate();

        assertThat(guard.subscriber("socket-1")).isNull();
        assertClosedOnNextMessage(socket, delegate);
    }

    // ---- 유휴 만료 ----

    @Test
    void afterIdleExpirySubscriberIsGoneAndSocketIsClosed() throws Exception {
        guard = new SessionWebSocketGuard(clock, ABSOLUTE);
        MockHttpSession session = loggedIn("2");
        clock.set(session.getLastAccessedTime());
        WebSocketHandler delegate = mock(WebSocketHandler.class);
        WebSocketSession socket = connect(session, delegate);

        clock.set(session.getLastAccessedTime() + Duration.ofSeconds(IDLE_SECONDS + 1).toMillis());

        assertThat(guard.subscriber("socket-1")).isNull();
        assertClosedOnNextMessage(socket, delegate);
    }

    @Test
    void withinIdleLimitSubscriberStays() throws Exception {
        guard = new SessionWebSocketGuard(clock, ABSOLUTE);
        MockHttpSession session = loggedIn("2");
        clock.set(session.getLastAccessedTime());
        connect(session, mock(WebSocketHandler.class));

        clock.set(session.getLastAccessedTime() + Duration.ofSeconds(IDLE_SECONDS - 1).toMillis());

        assertThat(guard.subscriber("socket-1")).isNotNull();
    }

    // ---- 절대 만료 (설정값 1시간) ----

    @Test
    void afterConfiguredAbsoluteExpirySubscriberIsGoneAndSocketIsClosed() throws Exception {
        guard = new SessionWebSocketGuard(clock, ABSOLUTE);
        MockHttpSession session = loggedIn("2");
        session.setMaxInactiveInterval((int) Duration.ofDays(1).toSeconds()); // 유휴 제한이 이유가 되지 않게
        clock.set(session.getCreationTime());
        WebSocketHandler delegate = mock(WebSocketHandler.class);
        WebSocketSession socket = connect(session, delegate);

        clock.set(session.getCreationTime() + ABSOLUTE.plusSeconds(1).toMillis());

        assertThat(guard.subscriber("socket-1")).isNull();
        assertClosedOnNextMessage(socket, delegate);
    }

    @Test
    void beforeConfiguredAbsoluteExpirySubscriberStays() throws Exception {
        guard = new SessionWebSocketGuard(clock, ABSOLUTE);
        MockHttpSession session = loggedIn("2");
        session.setMaxInactiveInterval((int) Duration.ofDays(1).toSeconds());
        clock.set(session.getCreationTime());
        connect(session, mock(WebSocketHandler.class));

        clock.set(session.getCreationTime() + ABSOLUTE.minusMinutes(1).toMillis());

        assertThat(guard.subscriber("socket-1")).isNotNull();
    }

    // ---- 연결 시점에 이미 무효 ----

    @Test
    void connectionWithInvalidatedSessionIsClosedImmediately() throws Exception {
        guard = new SessionWebSocketGuard(clock, ABSOLUTE);
        MockHttpSession session = loggedIn("2");
        clock.set(session.getCreationTime());
        Authentication auth = authentication(session);
        session.invalidate();
        WebSocketHandler delegate = mock(WebSocketHandler.class);
        WebSocketSession socket = socket(session, auth);

        guard.decorate(delegate).afterConnectionEstablished(socket);

        verify(socket).close(CloseStatus.POLICY_VIOLATION);
        verify(delegate, never()).afterConnectionEstablished(any());
        assertThat(guard.subscriber("socket-1")).isNull();
    }

    private WebSocketSession connect(MockHttpSession session, WebSocketHandler delegate) throws Exception {
        WebSocketSession socket = socket(session, authentication(session));
        guard.decorate(delegate).afterConnectionEstablished(socket);
        verify(delegate).afterConnectionEstablished(socket);
        return socket;
    }

    private void assertClosedOnNextMessage(WebSocketSession socket, WebSocketHandler delegate) throws Exception {
        guard.decorate(delegate).handleMessage(socket, new TextMessage("x"));
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
        verify(delegate, never()).handleMessage(any(), any());
    }

    private static WebSocketSession socket(MockHttpSession session, Authentication auth) {
        WebSocketSession socket = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("httpSession", session);
        attributes.put("httpSessionId", session.getId());
        when(socket.getId()).thenReturn("socket-1");
        when(socket.getAttributes()).thenReturn(attributes);
        when(socket.getPrincipal()).thenReturn(auth);
        return socket;
    }

    private static Authentication authentication(MockHttpSession session) {
        return ((SecurityContextImpl) session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .getAuthentication();
    }

    private static MockHttpSession loggedIn(String userId) {
        MockHttpSession session = new MockHttpSession();
        session.setMaxInactiveInterval(IDLE_SECONDS);
        var auth = UsernamePasswordAuthenticationToken.authenticated(userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(auth));
        return session;
    }

    /** 시험 중에 시각을 옮길 수 있는 시계. */
    static final class MutableClock extends Clock {
        private volatile long millis = System.currentTimeMillis();

        void set(long epochMillis) {
            this.millis = epochMillis;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }
    }
}
