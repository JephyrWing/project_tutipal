package com.my.tutipal_back.common.config;

import java.time.Duration;

import org.springframework.boot.convert.DurationStyle;

/**
 * 세션 절대 제한(app.session.absolute-timeout) 값 확인.
 * 비었거나 0 이하이거나 1분보다 짧으면 앱 시작을 막는다 (단위 없는 숫자는 밀리초라 "90" 같은 실수를 잡는다).
 * 설정을 받는 입구(SecurityConfig, SessionWebSocketGuard·LiveKitAccess 의 Spring 생성자)에서만 부른다.
 */
public final class SessionTimeouts {

    public static final String ABSOLUTE_PROPERTY = "app.session.absolute-timeout";
    static final Duration MIN_ABSOLUTE = Duration.ofMinutes(1);

    private SessionTimeouts() {
    }

    public static Duration requireValidAbsolute(Duration absoluteTimeout) {
        if (absoluteTimeout == null || absoluteTimeout.compareTo(MIN_ABSOLUTE) < 0) {
            throw new IllegalStateException(ABSOLUTE_PROPERTY + " must be at least " + MIN_ABSOLUTE
                    + " (got " + absoluteTimeout + ", e.g. use 12h)");
        }
        return absoluteTimeout;
    }

    public static Duration parseAbsolute(String raw) {
        if (raw == null || raw.isBlank()) {
            return requireValidAbsolute(null);
        }
        Duration parsed;
        try {
            parsed = DurationStyle.detectAndParse(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(ABSOLUTE_PROPERTY + " is not a valid duration: " + raw, e);
        }
        return requireValidAbsolute(parsed);
    }
}
