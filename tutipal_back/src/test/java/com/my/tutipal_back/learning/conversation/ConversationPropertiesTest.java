package com.my.tutipal_back.learning.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** PT-106: app.ai.conversations.* binding (summary numbers AC-20/21, retention AC-24). */
class ConversationPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ConversationProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void defaultsWhenNothingIsConfigured() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            ConversationProperties p = context.getBean(ConversationProperties.class);
            assertThat(p).isEqualTo(ConversationProperties.defaults());
            assertThat(p.retentionDays()).isZero();
            assertThat(p.retentionBatchSize()).isEqualTo(500);
            assertThat(p.summary().threshold()).isEqualTo(20);
            assertThat(p.summary().keepRecent()).isEqualTo(6);
            assertThat(p.summary().maxInput()).isEqualTo(40);
            assertThat(p.summary().slotWait()).isEqualTo(Duration.ofSeconds(5));
        });
    }

    @Test
    void bindsConfiguredValues() {
        runner.withPropertyValues(
                        "app.ai.conversations.retention-days=30",
                        "app.ai.conversations.retention-batch-size=100",
                        "app.ai.conversations.summary.threshold=10",
                        "app.ai.conversations.summary.keep-recent=4",
                        "app.ai.conversations.summary.max-input=12",
                        "app.ai.conversations.summary.slot-wait=2s")
                .run(context -> {
                    ConversationProperties p = context.getBean(ConversationProperties.class);
                    assertThat(p.retentionDays()).isEqualTo(30);
                    assertThat(p.retentionBatchSize()).isEqualTo(100);
                    assertThat(p.summary()).isEqualTo(
                            new ConversationProperties.Summary(10, 4, 12, Duration.ofSeconds(2)));
                });
    }
}
