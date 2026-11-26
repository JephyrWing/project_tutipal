package com.my.tutipal_back.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

/**
 * PT-25 NFR-SEC-01 / PT-100: 계속 쓰는 세션도 만든 지 절대 제한(app.session.absolute-timeout, 기본 12시간)이 지나면 버리고,
 * 요청은 그대로 다음으로 넘긴다(→ 비로그인 401).
 */
class SessionAbsoluteTimeoutFilterTest {

    private static final Duration DEFAULT_ABSOLUTE = Duration.ofHours(12);

    @Test
    void sessionOlderThanTwelveHoursIsInvalidated() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MockFilterChain chain = run(session, DEFAULT_ABSOLUTE, Duration.ofHours(12).plusSeconds(1));

        assertThat(session.isInvalid()).isTrue();
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void sessionWithinTwelveHoursIsKept() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MockFilterChain chain = run(session, DEFAULT_ABSOLUTE, Duration.ofHours(12).minusSeconds(1));

        assertThat(session.isInvalid()).isFalse();
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void sessionExactlyTwelveHoursOldIsStillKept() throws Exception {
        // 경계: "12시간이 지나면" 버린다 → 정확히 12시간은 유지 (필터 조건이 > 이다)
        MockHttpSession session = new MockHttpSession();
        run(session, DEFAULT_ABSOLUTE, Duration.ofHours(12));

        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void configuredOneHourAbsoluteTimeoutInvalidatesOlderSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MockFilterChain chain = run(session, Duration.ofHours(1), Duration.ofHours(1).plusSeconds(1));

        assertThat(session.isInvalid()).isTrue();
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void configuredOneHourAbsoluteTimeoutKeepsYoungerSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        run(session, Duration.ofHours(1), Duration.ofMinutes(59));

        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void requestWithoutSessionPassesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockFilterChain chain = new MockFilterChain();

        new SessionAbsoluteTimeoutFilter(Clock.systemUTC(), DEFAULT_ABSOLUTE)
                .doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(request.getSession(false)).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    private static MockFilterChain run(MockHttpSession session, Duration absoluteTimeout, Duration age) throws Exception {
        Instant now = Instant.ofEpochMilli(session.getCreationTime()).plus(age);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        MockFilterChain chain = new MockFilterChain();
        new SessionAbsoluteTimeoutFilter(Clock.fixed(now, ZoneOffset.UTC), absoluteTimeout)
                .doFilter(request, new MockHttpServletResponse(), chain);
        return chain;
    }
}
