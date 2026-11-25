package com.my.tutipal_back.common.startup;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

/** PT-110 AC-12: 기동 완료 시 배포 버전과 git SHA를 INFO 한 줄로 남긴다. */
@ExtendWith(OutputCaptureExtension.class)
class StartupVersionLoggerTest {

    private static final String PREFIX = "TUTIPAL started";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(StartupVersionLogger.class);

    private static long countOccurrences(String output, String needle) {
        long count = 0;
        int from = output.indexOf(needle);
        while (from >= 0) {
            count++;
            from = output.indexOf(needle, from + needle.length());
        }
        return count;
    }

    private static String lineContaining(String output, String needle) {
        return output.lines()
                .filter(line -> line.contains(needle))
                .findFirst()
                .orElse("");
    }

    @Test
    void logsVersionAndShaExactlyOnceAtInfo(CapturedOutput output) {
        new StartupVersionLogger("1.2.3", "abc123").logStartup();

        assertThat(countOccurrences(output.getOut(), "TUTIPAL started version=1.2.3 sha=abc123"))
                .isEqualTo(1);
        assertThat(countOccurrences(output.getOut(), PREFIX)).isEqualTo(1);
        assertThat(lineContaining(output.getOut(), PREFIX)).contains("INFO");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void logsUnknownWhenVersionAndShaAreBlankOrNull(String blank, CapturedOutput output) {
        new StartupVersionLogger(blank, blank).logStartup();

        assertThat(output.getOut()).contains("TUTIPAL started version=unknown sha=unknown");
        assertThat(countOccurrences(output.getOut(), PREFIX)).isEqualTo(1);
    }

    @Test
    void logsUnknownOnlyForTheMissingValue(CapturedOutput output) {
        new StartupVersionLogger("1.2.3", null).logStartup();

        assertThat(output.getOut()).contains("TUTIPAL started version=1.2.3 sha=unknown");
    }

    @Test
    void replacesLineBreaksSoValuesCannotForgeLogLines(CapturedOutput output) {
        new StartupVersionLogger("1.0\r\nWARN FORGED", "abc\nINFO FAKE").logStartup();

        assertThat(output.getOut()).doesNotContain("\nINFO FAKE").doesNotContain("\nWARN FORGED");
        assertThat(output.getOut()).contains("TUTIPAL started version=1.0__WARN_FORGED sha=abc_INFO_FAKE");
        assertThat(countOccurrences(output.getOut(), PREFIX)).isEqualTo(1);
    }

    @Test
    void defaultsToUnknownWhenEnvPropertiesAreMissing(CapturedOutput output) {
        contextRunner.run(context -> {
            publishReady(context.getSourceApplicationContext(ConfigurableApplicationContext.class));

            assertThat(output.getOut()).contains("TUTIPAL started version=unknown sha=unknown");
            assertThat(countOccurrences(output.getOut(), PREFIX)).isEqualTo(1);
        });
    }

    @Test
    void readsAppVersionAndGitShaPropertiesOnApplicationReady(CapturedOutput output) {
        contextRunner
                .withPropertyValues("APP_VERSION=0.0.1-SNAPSHOT", "GIT_SHA=9dc5517fb896064166e6b3708b73fd9a25f13da0")
                .run(context -> {
                    assertThat(output.getOut()).doesNotContain(PREFIX);

                    publishReady(context.getSourceApplicationContext(ConfigurableApplicationContext.class));

                    assertThat(output.getOut()).contains(
                            "TUTIPAL started version=0.0.1-SNAPSHOT sha=9dc5517fb896064166e6b3708b73fd9a25f13da0");
                    assertThat(countOccurrences(output.getOut(), PREFIX)).isEqualTo(1);
                });
    }

    private static void publishReady(ConfigurableApplicationContext context) {
        context.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0], context, Duration.ZERO));
    }
}
