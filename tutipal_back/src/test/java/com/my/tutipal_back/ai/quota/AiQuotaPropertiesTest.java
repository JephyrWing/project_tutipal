package com.my.tutipal_back.ai.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** PT-97: limit validation (B7), feature mapping, temporary defaults in application.yaml. */
class AiQuotaPropertiesTest {

    private static final Duration HOUR = Duration.ofHours(1);

    @Test
    void limitBelowOneIsRejected() {
        assertThatThrownBy(() -> new AiQuotaProperties.Limit(0, HOUR)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiQuotaProperties.Limit(-1, HOUR)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroNegativeOrMissingWindowIsRejected() {
        assertThatThrownBy(() -> new AiQuotaProperties.Limit(1, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiQuotaProperties.Limit(1, Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiQuotaProperties.Limit(1, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validLimitKeepsValues() {
        var limit = new AiQuotaProperties.Limit(1, Duration.ofMillis(1));
        assertThat(limit.limit()).isEqualTo(1);
        assertThat(limit.window()).isEqualTo(Duration.ofMillis(1));
    }

    @Test
    void ofReturnsTheLimitOfEachFeature() {
        var auto = new AiQuotaProperties.Limit(1, HOUR);
        var manual = new AiQuotaProperties.Limit(2, HOUR);
        var chat = new AiQuotaProperties.Limit(3, HOUR);
        var quiz = new AiQuotaProperties.Limit(4, HOUR);
        var props = new AiQuotaProperties(auto, manual, chat, quiz, Duration.ofMinutes(10));

        assertThat(props.of(AiFeature.SCREEN_AUTO)).isEqualTo(auto);
        assertThat(props.of(AiFeature.SCREEN_MANUAL)).isEqualTo(manual);
        assertThat(props.of(AiFeature.CHAT)).isEqualTo(chat);
        assertThat(props.of(AiFeature.QUIZ_GENERATE)).isEqualTo(quiz);
    }

    @Test
    void applicationYamlBindsTemporaryDefaults() throws Exception {
        var env = new StandardEnvironment();
        for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))) {
            env.getPropertySources().addLast(source);
        }

        AiQuotaProperties props = Binder.get(env).bind("app.ai.quota", AiQuotaProperties.class).get();

        assertThat(props.screenAuto()).isEqualTo(new AiQuotaProperties.Limit(120, HOUR));
        assertThat(props.screenManual()).isEqualTo(new AiQuotaProperties.Limit(30, HOUR));
        assertThat(props.chat()).isEqualTo(new AiQuotaProperties.Limit(60, HOUR));
        assertThat(props.quizGenerate()).isEqualTo(new AiQuotaProperties.Limit(20, HOUR));
        assertThat(props.cleanupInterval()).isEqualTo(Duration.ofMinutes(10));
    }
}
