package com.my.tutipal_back.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

/** PT-100: app.dev-login 은 @ConditionalOnProperty(havingValue = "true") 와 같이 "true"(대소문자 무시)만 켠 것으로 본다. */
class DevLoginRequestedValueTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DevLoginGuard.class);

    @ParameterizedTest
    @ValueSource(strings = {"on", "yes", "1", "false", ""})
    void nonTrueValuesAreNotRequestedAndProdStarts(String value) {
        MockEnvironment env = new MockEnvironment().withProperty(DevLogin.PROPERTY, value);
        env.setActiveProfiles("local");
        assertThat(DevLogin.requested(env)).isFalse();
        assertThat(DevLogin.enabled(env)).isFalse();

        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues(DevLogin.PROPERTY + "=" + value)
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", " True "})
    void trueIgnoringCaseIsRequested(String value) {
        MockEnvironment env = new MockEnvironment().withProperty(DevLogin.PROPERTY, value);
        env.setActiveProfiles("local");

        assertThat(DevLogin.requested(env)).isTrue();
        assertThat(DevLogin.enabled(env)).isTrue();
    }
}
