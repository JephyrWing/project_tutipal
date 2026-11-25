package com.my.tutipal_back.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** PT-100: 개발 로그인(app.dev-login=true)은 local 프로필에서만 허용하고, 그 밖에서는 앱이 뜨지 않게 막는다. */
class DevLoginGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DevLoginGuard.class);

    @Test
    void prodProfileWithDevLoginFailsStartup() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues("app.dev-login=true")
                .run(ctx -> assertFailedWithIllegalState(ctx.getStartupFailure()));
    }

    @Test
    void noProfileWithDevLoginFailsStartup() {
        runner.withPropertyValues("app.dev-login=true")
                .run(ctx -> assertFailedWithIllegalState(ctx.getStartupFailure()));
    }

    @Test
    void localAndProdTogetherWithDevLoginFailsStartup() {
        // 운영 프로필이 하나라도 켜져 있으면 local 이 같이 있어도 허용하지 않는다
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("local", "prod"))
                .withPropertyValues("app.dev-login=true")
                .run(ctx -> assertFailedWithIllegalState(ctx.getStartupFailure()));
    }

    @Test
    void localProfileWithDevLoginStarts() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("local"))
                .withPropertyValues("app.dev-login=true")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(DevLoginGuard.class);
                });
    }

    @Test
    void prodProfileWithDevLoginOffStarts() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues("app.dev-login=false")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void prodProfileWithoutDevLoginPropertyStarts() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    private static void assertFailedWithIllegalState(Throwable failure) {
        assertThat(failure).as("startup failure").isNotNull();
        Throwable cause = failure;
        while (cause != null && !(cause instanceof IllegalStateException)) {
            cause = cause.getCause();
        }
        assertThat(cause).as("IllegalStateException in cause chain of %s", failure).isInstanceOf(IllegalStateException.class);
    }
}
