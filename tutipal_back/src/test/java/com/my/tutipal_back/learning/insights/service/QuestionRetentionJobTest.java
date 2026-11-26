package com.my.tutipal_back.learning.insights.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;

import com.my.tutipal_back.learning.insights.repository.ChatQuestionStore;

/** PT-87 AC-7 / D6: delete rows older than the retention, 1000 at a time, with an injected clock. */
class QuestionRetentionJobTest {

    private static final Instant NOW = Instant.parse("2026-11-25T03:00:00Z");
    private static final Instant CUTOFF = NOW.minus(Duration.ofDays(30));

    private final ChatQuestionStore store = mock(ChatQuestionStore.class);
    private final QuestionRetentionJob job =
            new QuestionRetentionJob(store, Duration.ofDays(30), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void deletesOnlyRowsOlderThanTheRetentionUsingTheClock() {
        when(store.deleteCreatedBefore(any(Instant.class), anyInt())).thenReturn(3);

        job.purgeExpired();

        verify(store).deleteCreatedBefore(CUTOFF, 1000);
    }

    @Test
    void repeatsInBatchesOfOneThousandUntilAShortBatch() {
        when(store.deleteCreatedBefore(any(Instant.class), anyInt())).thenReturn(1000, 1000, 7);

        job.purgeExpired();

        verify(store, times(3)).deleteCreatedBefore(CUTOFF, 1000);
    }

    @Test
    void stopsWhenNothingIsLeft() {
        when(store.deleteCreatedBefore(any(Instant.class), anyInt())).thenReturn(1000, 0);

        job.purgeExpired();

        verify(store, times(2)).deleteCreatedBefore(CUTOFF, 1000);
    }

    @Test
    void cutoffMovesWithTheClock() {
        when(store.deleteCreatedBefore(any(Instant.class), anyInt())).thenReturn(0);
        Instant later = NOW.plus(Duration.ofDays(2));
        var laterJob = new QuestionRetentionJob(store, Duration.ofDays(30), Clock.fixed(later, ZoneOffset.UTC));

        laterJob.purgeExpired();

        verify(store).deleteCreatedBefore(later.minus(Duration.ofDays(30)), 1000);
    }

    @Test
    void runsOnTheConfiguredScheduleWithoutACodeDefault() throws Exception {
        Method purge = QuestionRetentionJob.class.getMethod("purgeExpired");
        Scheduled scheduled = purge.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        String interval = scheduled.fixedDelayString() + scheduled.fixedRateString();
        assertThat(interval).isEqualTo("${app.insights.questions.cleanup-interval}");
    }

    @Test
    void applicationYamlDefinesRetentionAndCleanupIntervalAndInsightsQuota() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        Properties props = yaml.getObject();

        assertThat(props).isNotNull();
        assertThat(DurationStyle.detectAndParse(props.getProperty("app.insights.questions.retention")))
                .isEqualTo(Duration.ofDays(30));
        assertThat(props.getProperty("app.insights.questions.cleanup-interval")).isNotBlank();
        assertThat(DurationStyle.detectAndParse(props.getProperty("app.insights.questions.cleanup-interval")))
                .isPositive();
        assertThat(props.getProperty("app.ai.quota.insights.limit")).isNotBlank();
        assertThat(props.getProperty("app.ai.quota.insights.window")).isNotBlank();
    }
}
