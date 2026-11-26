package com.my.tutipal_back.learning.focus;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** PT-90: app.focus.* binding with the planned defaults; invalid values stop the application from starting. */
class FocusPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FocusProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void defaultsMatchThePlan() {
        FocusProperties d = FocusProperties.defaults();

        assertThat(d.consentVersion()).isEqualTo("2026-11-26");
        assertThat(d.retentionDays()).isEqualTo(30);
        assertThat(d.retentionBatchSize()).isEqualTo(500);
        assertThat(d.submitMinInterval()).isEqualTo(Duration.ofSeconds(50));
        assertThat(d.staleAfter()).isEqualTo(Duration.ofMinutes(3));
        assertThat(d.zone()).isEqualTo(ZoneId.of("Asia/Seoul"));
        assertThat(d.predictTimeout()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void bindsWithDefaultsWhenNothingIsConfigured() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(FocusProperties.class)).isEqualTo(FocusProperties.defaults());
        });
    }

    @Test
    void bindsConfiguredValues() {
        runner.withPropertyValues(
                        "app.focus.consent-version=2027-01-01",
                        "app.focus.retention-days=7",
                        "app.focus.retention-batch-size=100",
                        "app.focus.submit-min-interval=55s",
                        "app.focus.stale-after=5m",
                        "app.focus.zone=UTC",
                        "app.focus.predict-timeout=1s")
                .run(context -> {
                    FocusProperties p = context.getBean(FocusProperties.class);
                    assertThat(p.consentVersion()).isEqualTo("2027-01-01");
                    assertThat(p.retentionDays()).isEqualTo(7);
                    assertThat(p.retentionBatchSize()).isEqualTo(100);
                    assertThat(p.submitMinInterval()).isEqualTo(Duration.ofSeconds(55));
                    assertThat(p.staleAfter()).isEqualTo(Duration.ofMinutes(5));
                    assertThat(p.zone()).isEqualTo(ZoneId.of("UTC"));
                    assertThat(p.predictTimeout()).isEqualTo(Duration.ofSeconds(1));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "app.focus.consent-version=",
            "app.focus.consent-version=123456789012345678901",
            "app.focus.retention-days=0",
            "app.focus.retention-batch-size=0",
            "app.focus.submit-min-interval=0s",
            "app.focus.stale-after=0s",
            "app.focus.predict-timeout=0s",
            "app.focus.zone=Mars/Base",
    })
    void invalidValuesFailStartup(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void maxSampleAgeDaysBindsWithDefault31() {
        runner.run(context -> assertThat(context.getBean(FocusProperties.class).maxSampleAgeDays()).isEqualTo(31));
        runner.withPropertyValues("app.focus.max-sample-age-days=45")
                .run(context -> assertThat(context.getBean(FocusProperties.class).maxSampleAgeDays()).isEqualTo(45));
    }

    @Test
    void maxSampleAgeDaysBelowOneFailsStartup() {
        runner.withPropertyValues("app.focus.max-sample-age-days=0").run(context -> assertThat(context).hasFailed());
    }
}
