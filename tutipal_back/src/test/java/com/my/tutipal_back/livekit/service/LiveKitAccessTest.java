package com.my.tutipal_back.livekit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.livekit.dto.ScreenShareDtos.JoinInfo;

/**
 * PT-100: LiveKit 입장권은 발급한 로그인 세션에 묶인다.
 * 로그아웃·유휴 만료·절대 만료(app.session.absolute-timeout) 뒤나 다른 세션에서는 401 로 거절한다.
 */
class LiveKitAccessTest {

    private static final Duration ABSOLUTE = Duration.ofHours(1);
    private static final int IDLE_SECONDS = 30 * 60;
    private static final Long USER = 2L;
    private static final Long SESSION_ID = 10L;

    private final ScreenShareService shares = mock(ScreenShareService.class);
    private final LiveKitAdmin admin = mock(LiveKitAdmin.class);
    private final MutableClock clock = new MutableClock();
    private LiveKitAccess access;

    @BeforeEach
    void setUp() {
        when(admin.ready()).thenReturn(true);
        when(shares.classToken(USER, SESSION_ID)).thenReturn(new JoinInfo("/livekit", "upstream-token", "class-10", "user-2"));
        access = new LiveKitAccess(shares, admin, clock, ABSOLUTE);
    }

    @Test
    void validSessionResolvesTicket() {
        MockHttpSession session = loggedIn(USER);
        clock.set(session.getCreationTime());
        String key = issue(session);

        LiveKitAccess.Access resolved = access.resolve(key, session);

        assertThat(resolved.userId()).isEqualTo(USER);
        assertThat(resolved.target()).isEqualTo(SESSION_ID);
        assertThat(access.upstream(resolved).token()).isEqualTo("upstream-token");
    }

    @Test
    void issuedTicketIsSessionBoundUrl() {
        MockHttpSession session = loggedIn(USER);
        clock.set(session.getCreationTime());

        JoinInfo info = access.issue(USER, false, SESSION_ID, session);

        assertThat(info.url()).startsWith("/livekit/");
        assertThat(info.token()).isNotEqualTo("upstream-token");
    }

    @Test
    void differentSessionCannotUseTicket() {
        MockHttpSession session = loggedIn(USER);
        clock.set(session.getCreationTime());
        String key = issue(session);

        assertUnauthorized(() -> access.resolve(key, loggedIn(USER)));
    }

    @Test
    void missingSessionCannotUseTicket() {
        MockHttpSession session = loggedIn(USER);
        clock.set(session.getCreationTime());
        String key = issue(session);

        assertUnauthorized(() -> access.resolve(key, null));
    }

    @Test
    void unknownTicketIsUnauthorized() {
        MockHttpSession session = loggedIn(USER);
        clock.set(session.getCreationTime());

        assertUnauthorized(() -> access.resolve("not-a-ticket", session));
    }

    @Test
    void afterLogoutTicketAndUpstreamAreUnauthorized() {
        MockHttpSession session = loggedIn(USER);
        clock.set(session.getCreationTime());
        String key = issue(session);
        LiveKitAccess.Access resolved = access.resolve(key, session);

        session.invalidate();

        assertUnauthorized(() -> access.resolve(key, session));
        assertUnauthorized(() -> access.upstream(resolved));
    }

    @Test
    void afterIdleExpiryTicketAndUpstreamAreUnauthorized() {
        MockHttpSession session = loggedIn(USER);
        clock.set(session.getLastAccessedTime());
        String key = issue(session);
        LiveKitAccess.Access resolved = access.resolve(key, session);

        clock.set(session.getLastAccessedTime() + Duration.ofSeconds(IDLE_SECONDS + 1).toMillis());

        assertUnauthorized(() -> access.resolve(key, session));
        assertUnauthorized(() -> access.upstream(resolved));
    }

    @Test
    void afterConfiguredAbsoluteExpiryTicketAndUpstreamAreUnauthorized() {
        MockHttpSession session = loggedIn(USER);
        session.setMaxInactiveInterval((int) Duration.ofDays(1).toSeconds()); // 유휴 제한이 이유가 되지 않게
        clock.set(session.getCreationTime());
        String key = issue(session);
        LiveKitAccess.Access resolved = access.resolve(key, session);

        clock.set(session.getCreationTime() + ABSOLUTE.plusSeconds(1).toMillis());

        assertUnauthorized(() -> access.resolve(key, session));
        assertUnauthorized(() -> access.upstream(resolved));
    }

    @Test
    void beforeConfiguredAbsoluteExpiryTicketStillWorks() {
        MockHttpSession session = loggedIn(USER);
        session.setMaxInactiveInterval((int) Duration.ofDays(1).toSeconds());
        clock.set(session.getCreationTime());
        String key = issue(session);

        clock.set(session.getCreationTime() + ABSOLUTE.minusMinutes(1).toMillis());

        assertThat(access.resolve(key, session).userId()).isEqualTo(USER);
    }

    private String issue(MockHttpSession session) {
        JoinInfo info = access.issue(USER, false, SESSION_ID, session);
        return info.url().substring("/livekit/".length());
    }

    private static void assertUnauthorized(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    private static MockHttpSession loggedIn(Long userId) {
        MockHttpSession session = new MockHttpSession();
        session.setMaxInactiveInterval(IDLE_SECONDS);
        var auth = UsernamePasswordAuthenticationToken.authenticated(String.valueOf(userId), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
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
