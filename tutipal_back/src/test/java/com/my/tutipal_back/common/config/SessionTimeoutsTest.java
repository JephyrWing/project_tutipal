package com.my.tutipal_back.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.my.tutipal_back.common.realtime.SessionWebSocketGuard;
import com.my.tutipal_back.livekit.service.LiveKitAccess;
import com.my.tutipal_back.livekit.service.LiveKitAdmin;
import com.my.tutipal_back.livekit.service.ScreenShareService;

/** PT-100: 절대 제한 값이 비었거나 0 이하이거나 1분 미만이면 시작을 막는다 (HTTP 필터 · STOMP 가드 · LiveKit 공통). */
class SessionTimeoutsTest {

    // 실제 앱(SpringApplication)과 같이 "12h" 같은 문자열을 Duration 으로 바꾸는 변환기를 쓴다
    private final ApplicationContextRunner guardRunner = new ApplicationContextRunner()
            .withInitializer(ctx -> ctx.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(SessionWebSocketGuard.class);

    private final ApplicationContextRunner liveKitRunner = new ApplicationContextRunner()
            .withInitializer(ctx -> ctx.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
            .withBean(ScreenShareService.class, () -> mock(ScreenShareService.class))
            .withBean(LiveKitAdmin.class, () -> mock(LiveKitAdmin.class))
            .withUserConfiguration(LiveKitAccess.class);

    @ParameterizedTest
    @ValueSource(strings = {"-1h", "0s", "90", "", "  ", "59s", "abc"})
    void invalidAbsoluteTimeoutTextIsRejected(String raw) {
        assertThatThrownBy(() -> SessionTimeouts.parseAbsolute(raw)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nullAbsoluteTimeoutIsRejected() {
        assertThatThrownBy(() -> SessionTimeouts.requireValidAbsolute(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SessionTimeouts.parseAbsolute(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validAbsoluteTimeoutIsAccepted() {
        assertThat(SessionTimeouts.parseAbsolute("12h")).isEqualTo(Duration.ofHours(12));
        assertThat(SessionTimeouts.parseAbsolute("1m")).isEqualTo(Duration.ofMinutes(1));
        assertThat(SessionTimeouts.requireValidAbsolute(Duration.ofHours(12))).isEqualTo(Duration.ofHours(12));
    }

    @Test
    void defaultConfigValueIsValid() {
        String raw = CookieProfileConfigTest.environment(Map.of()).getProperty(SessionTimeouts.ABSOLUTE_PROPERTY);

        assertThat(SessionTimeouts.parseAbsolute(raw)).isEqualTo(Duration.ofHours(12));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1h", "0s", "90", ""})
    void webSocketGuardFailsStartupForInvalidAbsoluteTimeout(String raw) {
        guardRunner.withPropertyValues(SessionTimeouts.ABSOLUTE_PROPERTY + "=" + raw)
                .run(ctx -> assertFailedWithIllegalState(ctx.getStartupFailure()));
    }

    @Test
    void webSocketGuardStartsWithTwelveHours() {
        guardRunner.withPropertyValues(SessionTimeouts.ABSOLUTE_PROPERTY + "=12h")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(SessionWebSocketGuard.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1h", "0s", "90", ""})
    void liveKitAccessFailsStartupForInvalidAbsoluteTimeout(String raw) {
        liveKitRunner.withPropertyValues(SessionTimeouts.ABSOLUTE_PROPERTY + "=" + raw)
                .run(ctx -> assertFailedWithIllegalState(ctx.getStartupFailure()));
    }

    @Test
    void liveKitAccessStartsWithTwelveHours() {
        liveKitRunner.withPropertyValues(SessionTimeouts.ABSOLUTE_PROPERTY + "=12h")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(LiveKitAccess.class));
    }

    private static void assertFailedWithIllegalState(Throwable failure) {
        assertThat(failure).as("startup failure").isNotNull();
        Throwable cause = failure;
        while (cause != null && !(cause instanceof IllegalStateException)) {
            cause = cause.getCause();
        }
        assertThat(cause).as("IllegalStateException in cause chain of %s", failure).isInstanceOf(IllegalStateException.class);
        // 변환 실패가 아니라 SessionTimeouts 검사로 막혔는지 확인한다
        assertThat(cause).hasMessageContaining(SessionTimeouts.ABSOLUTE_PROPERTY);
    }
}
