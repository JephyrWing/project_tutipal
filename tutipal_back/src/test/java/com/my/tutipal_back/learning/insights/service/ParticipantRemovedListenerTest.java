package com.my.tutipal_back.learning.insights.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;

import com.my.tutipal_back.learning.insights.repository.ChatQuestionStore;
import com.my.tutipal_back.sessions.service.StudySessionService;

/** PT-87 AC-7: leaving or being removed deletes that student's questions in that session only. */
class ParticipantRemovedListenerTest {

    private final ChatQuestionStore store = mock(ChatQuestionStore.class);
    private final ParticipantRemovedListener listener = new ParticipantRemovedListener(store);

    @Test
    void deletesQuestionsOfThatStudentInThatSessionOnly() {
        listener.onParticipantRemoved(new StudySessionService.ParticipantRemoved(10L, 21L));

        verify(store).deleteBySessionAndUser(10L, 21L);
        verifyNoMoreInteractions(store);
    }

    @Test
    void listensToTheParticipantRemovedEvent() throws Exception {
        Method handler = ParticipantRemovedListener.class.getMethod("onParticipantRemoved",
                StudySessionService.ParticipantRemoved.class);

        assertThat(AnnotatedElementUtils.hasAnnotation(handler, EventListener.class)).isTrue();
    }
}
