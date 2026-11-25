package com.my.tutipal_back.ai.quota;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** PT-97: AiQuota is created by Spring from app.ai.quota, and a missing feature fails startup. */
class AiQuotaContextTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiQuotaProperties.class)
    @Import(AiQuota.class)
    static class QuotaConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(QuotaConfig.class);

    private static final String[] ALL = {
        "app.ai.quota.screen-auto.limit=1", "app.ai.quota.screen-auto.window=1h",
        "app.ai.quota.screen-manual.limit=2", "app.ai.quota.screen-manual.window=1h",
        "app.ai.quota.chat.limit=3", "app.ai.quota.chat.window=30m",
        "app.ai.quota.quiz-generate.limit=4", "app.ai.quota.quiz-generate.window=1h",
        "app.ai.quota.cleanup-interval=10m",
    };

    @Test
    void createsQuotaFromProperties() {
        runner.withPropertyValues(ALL).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(AiQuota.class);
            assertThat(context.getBean(AiQuotaProperties.class).of(AiFeature.CHAT))
                    .isEqualTo(new AiQuotaProperties.Limit(3, Duration.ofMinutes(30)));
            AiQuota quota = context.getBean(AiQuota.class);
            quota.reserve(7L, AiFeature.SCREEN_AUTO);
            assertThat(AiQuotaFixtures.hasRoom(quota, 7L, AiFeature.SCREEN_AUTO)).isFalse();
        });
    }

    @Test
    void missingFeatureFailsStartup() {
        runner.withPropertyValues("app.ai.quota.screen-auto.limit=1", "app.ai.quota.screen-auto.window=1h",
                        "app.ai.quota.cleanup-interval=10m")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("screen-manual");
                });
    }

    @Test
    void zeroLimitFailsStartup() {
        runner.withPropertyValues(ALL).withPropertyValues("app.ai.quota.chat.limit=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("limit");
                });
    }
}
