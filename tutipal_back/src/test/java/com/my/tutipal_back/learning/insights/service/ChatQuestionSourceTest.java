package com.my.tutipal_back.learning.insights.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.my.tutipal_back.learning.insights.repository.ChatQuestionStore;
import com.my.tutipal_back.learning.insights.repository.QuestionRow;
import com.my.tutipal_back.learning.insights.repository.RawQuestion;

/** PT-87 Codex R1: the analysis never reads rows older than the retention, even before the purge job ran. */
class ChatQuestionSourceTest {

    private static final Instant NOW = Instant.parse("2026-11-25T03:00:00Z");

    private final ChatQuestionStore store = mock(ChatQuestionStore.class);

    @Test
    void readsOnlyRowsYoungerThanTheRetentionUsingTheClock() {
        List<QuestionRow> rows = List.of(new QuestionRow(1L, 2L, new RawQuestion("합성 질문입니다")));
        Instant cutoff = NOW.minus(Duration.ofDays(30));
        when(store.recentQuestions(10L, 512, cutoff)).thenReturn(rows);
        ChatQuestionSource source = new ChatQuestionSource(store, Duration.ofDays(30), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(source.recentQuestions(10L, 512)).isSameAs(rows);
        verify(store).recentQuestions(10L, 512, cutoff);
    }

    @Test
    void nonPositiveRetentionFailsFast() {
        assertThatThrownBy(() -> new ChatQuestionSource(store, Duration.ZERO, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
