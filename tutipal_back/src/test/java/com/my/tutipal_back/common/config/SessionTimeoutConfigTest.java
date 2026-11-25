package com.my.tutipal_back.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

/**
 * PT-100: 세션 유휴 제한(server.servlet.session.timeout)과 절대 제한(app.session.absolute-timeout)이
 * application.yaml 한 곳에서 기본값과 환경 변수로 정해지는지 확인한다.
 */
class SessionTimeoutConfigTest {

    private static final String IDLE = "server.servlet.session.timeout";
    private static final String ABSOLUTE = "app.session.absolute-timeout";

    @Test
    void idleTimeoutDefaultsToTwoHours() {
        StandardEnvironment env = CookieProfileConfigTest.environment(Map.of());

        assertThat(env.getProperty(IDLE)).isEqualTo("2h");
    }

    @Test
    void idleTimeoutComesFromSessionIdleTimeoutEnvironmentVariable() {
        StandardEnvironment env = CookieProfileConfigTest.environment(Map.of("SESSION_IDLE_TIMEOUT", "30m"));

        assertThat(env.getProperty(IDLE)).isEqualTo("30m");
    }

    @Test
    void absoluteTimeoutDefaultsToTwelveHours() {
        StandardEnvironment env = CookieProfileConfigTest.environment(Map.of());

        assertThat(env.getProperty(ABSOLUTE)).isEqualTo("12h");
    }

    @Test
    void absoluteTimeoutComesFromSessionAbsoluteTimeoutEnvironmentVariable() {
        StandardEnvironment env = CookieProfileConfigTest.environment(Map.of("SESSION_ABSOLUTE_TIMEOUT", "6h"));

        assertThat(env.getProperty(ABSOLUTE)).isEqualTo("6h");
    }

    @Test
    void prodProfileKeepsTheSameTimeoutSources() {
        StandardEnvironment env = CookieProfileConfigTest.environment(
                Map.of("SESSION_IDLE_TIMEOUT", "45m", "SESSION_ABSOLUTE_TIMEOUT", "8h"), "application-prod.yaml");

        assertThat(env.getProperty(IDLE)).isEqualTo("45m");
        assertThat(env.getProperty(ABSOLUTE)).isEqualTo("8h");
    }
}
