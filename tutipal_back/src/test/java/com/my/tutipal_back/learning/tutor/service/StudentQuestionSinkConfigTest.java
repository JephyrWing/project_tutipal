package com.my.tutipal_back.learning.tutor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.my.tutipal_back.learning.insights.repository.ChatQuestionStore;
import com.my.tutipal_back.learning.insights.service.ChatQuestionRecorder;

/** PT-87 D3: NONE is only the fallback; the insights recorder replaces it instead of failing startup with two beans. */
class StudentQuestionSinkConfigTest {

    @Test
    void noneIsRegisteredWhenNothingElseIs() {
        new ApplicationContextRunner().withUserConfiguration(StudentQuestionSinkConfig.class).run(context ->
                assertThat(context).hasSingleBean(StudentQuestionSink.class)
                        .getBean(StudentQuestionSink.class).isSameAs(StudentQuestionSink.NONE));
    }

    @Test
    void theInsightsRecorderReplacesNone() {
        new ApplicationContextRunner()
                .withBean(ChatQuestionStore.class, () -> mock(ChatQuestionStore.class))
                .withBean(ChatQuestionRecorder.class)
                .withUserConfiguration(StudentQuestionSinkConfig.class)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(StudentQuestionSink.class)
                        .getBean(StudentQuestionSink.class).isInstanceOf(ChatQuestionRecorder.class));
    }
}
