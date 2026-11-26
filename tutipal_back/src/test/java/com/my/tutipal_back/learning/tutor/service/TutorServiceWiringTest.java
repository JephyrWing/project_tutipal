package com.my.tutipal_back.learning.tutor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.ai.client.AiSlots;
import com.my.tutipal_back.ai.quota.AiQuota;
import com.my.tutipal_back.learning.quiz.service.QuizVariantAccess;
import com.my.tutipal_back.rag.service.RagSearchService;
import com.my.tutipal_back.sessions.service.StudySessionService;

/**
 * PT-87 Round 1 F5: TutorService keeps the old 8-argument constructor for other branches, and Spring still builds it
 * through the 9-argument one so the real question sink is injected.
 */
class TutorServiceWiringTest {

    private final StudentQuestionSink sink = mock(StudentQuestionSink.class);

    @Test
    void springUsesTheConstructorWithTheQuestionSink() {
        new ApplicationContextRunner()
                .withBean(AiClient.class, () -> mock(AiClient.class))
                .withBean(StudySessionService.class, () -> mock(StudySessionService.class))
                .withBean(RagSearchService.class, () -> mock(RagSearchService.class))
                .withBean(AiSlots.class, () -> mock(AiSlots.class))
                .withBean(AiQuota.class, () -> mock(AiQuota.class))
                .withBean(QuizVariantAccess.class, () -> mock(QuizVariantAccess.class))
                .withBean(AgentContextAssembler.class, () -> new AgentContextAssembler(new NoStoredConversationMemory()))
                .withBean(StudentQuestionSink.class, () -> sink)
                .withBean(TutorService.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(TutorService.class);
                    assertThat(ReflectionTestUtils.getField(context.getBean(TutorService.class), "questionSink"))
                            .isSameAs(sink);
                });
    }

    @Test
    void theEightArgumentConstructorRecordsNothing() {
        TutorService service = new TutorService(mock(AiClient.class), mock(StudySessionService.class),
                mock(RagSearchService.class), mock(AiSlots.class), mock(AiQuota.class), null, null, false);

        assertThat(ReflectionTestUtils.getField(service, "questionSink")).isSameAs(StudentQuestionSink.NONE);
    }
}
