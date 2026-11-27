package com.my.tutipal_back.learning.quiz.service;

import static com.my.tutipal_back.ai.quota.AiQuotaFixtures.hasRoom;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.my.tutipal_back.ai.client.AiUnavailableException;
import com.my.tutipal_back.ai.quota.AiFeature;
import com.my.tutipal_back.ai.quota.AiQuota;
import com.my.tutipal_back.ai.quota.AiQuotaExceededException;
import com.my.tutipal_back.ai.quota.AiQuotaFixtures;
import com.my.tutipal_back.ai.quota.MutableClock;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.common.realtime.SessionEvents;
import com.my.tutipal_back.learning.quiz.dto.QuizDtos.GenerateQuizRequest;
import com.my.tutipal_back.learning.quiz.dto.QuizDtos.QuizDraft;
import com.my.tutipal_back.learning.quiz.entity.QuizType;
import com.my.tutipal_back.learning.quiz.repository.QuizAnswerRepository;
import com.my.tutipal_back.learning.quiz.repository.QuizRepository;
import com.my.tutipal_back.sessions.dto.ChatSettings;
import com.my.tutipal_back.sessions.service.StudySessionService;
import com.my.tutipal_back.users.service.UserService;

/** PT-97 S5/S6: AI quiz generation quota (teacher check first, malformed counted, AI down refunded). */
class QuizServiceQuotaTest {

    private static final long TEACHER = 2L;
    private static final long SESSION = 10L;
    private static final GenerateQuizRequest REQ = new GenerateQuizRequest(QuizType.CHOICE, "미분");
    private static final ChatSettings SETTINGS = new ChatSettings("수학", 3, false, false);

    private final StudySessionService sessions = mock(StudySessionService.class);
    private final QuizAi quizAi = mock(QuizAi.class);
    private final QuizDrafts drafts = mock(QuizDrafts.class);
    private final AiQuota quota = AiQuotaFixtures.quota(1, Duration.ofHours(1),
            new MutableClock(Instant.parse("2026-11-25T00:00:00Z")));
    private final QuizService service = new QuizService(mock(QuizRepository.class), mock(QuizAnswerRepository.class),
            sessions, mock(UserService.class), quizAi, mock(SessionEvents.class), quota, drafts, mock(QuizGradeWriter.class));

    @BeforeEach
    void settings() {
        when(sessions.chatSettings(TEACHER, SESSION)).thenReturn(SETTINGS);
    }

    @Test
    void successIsCountedAndReturnsDraft() {
        var draft = new QuizDraft(QuizType.CHOICE, "미분이란?", List.of("a", "b"), 0, null);
        var registered = new QuizDraft(draft.type(), draft.question(), draft.choices(), draft.answerIndex(), null, 91L);
        when(quizAi.generate(SETTINGS, QuizType.CHOICE, "미분")).thenReturn(draft);
        when(drafts.register(TEACHER, SESSION, draft)).thenReturn(registered);

        assertThat(service.generate(TEACHER, SESSION, REQ)).isEqualTo(registered);
        assertThat(hasRoom(quota, TEACHER, AiFeature.QUIZ_GENERATE)).isFalse();
    }

    @Test
    void nonTeacherIsRejectedWithoutReservation() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "선생님만")).when(sessions).requireTeacherOf(TEACHER, SESSION);

        assertThatThrownBy(() -> service.generate(TEACHER, SESSION, REQ))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(hasRoom(quota, TEACHER, AiFeature.QUIZ_GENERATE)).isTrue();
        verifyNoInteractions(quizAi);
    }

    @Test
    void missingSessionIsRejectedWithoutReservation() {
        doThrow(new ApiException(HttpStatus.NOT_FOUND, "세션 없음")).when(sessions).requireTeacherOf(TEACHER, SESSION);

        assertThatThrownBy(() -> service.generate(TEACHER, SESSION, REQ)).isInstanceOf(ApiException.class);

        assertThat(hasRoom(quota, TEACHER, AiFeature.QUIZ_GENERATE)).isTrue();
        verifyNoInteractions(quizAi);
    }

    @Test
    void malformedModelOutputIsCounted() {
        var malformed = new ApiException(HttpStatus.BAD_GATEWAY, "AI 가 문제를 제대로 만들지 못했어요");
        when(quizAi.generate(any(), any(), anyString())).thenThrow(malformed);

        assertThatThrownBy(() -> service.generate(TEACHER, SESSION, REQ)).isSameAs(malformed);

        assertThat(hasRoom(quota, TEACHER, AiFeature.QUIZ_GENERATE)).isFalse();
    }

    @Test
    void aiDownIsRefunded() {
        when(quizAi.generate(any(), any(), anyString())).thenThrow(new AiUnavailableException());

        assertThatThrownBy(() -> service.generate(TEACHER, SESSION, REQ)).isInstanceOf(AiUnavailableException.class);

        assertThat(hasRoom(quota, TEACHER, AiFeature.QUIZ_GENERATE)).isTrue();
    }

    @Test
    void slotWaitTimeoutIsRefunded() {
        var busy = new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "지금 질문이 많아요");
        when(quizAi.generate(any(), any(), anyString())).thenThrow(busy);

        assertThatThrownBy(() -> service.generate(TEACHER, SESSION, REQ)).isSameAs(busy);

        assertThat(hasRoom(quota, TEACHER, AiFeature.QUIZ_GENERATE)).isTrue();
    }

    // S6
    @Test
    void overQuotaNeverCallsQuizAi() {
        quota.reserve(TEACHER, AiFeature.QUIZ_GENERATE);

        assertThatThrownBy(() -> service.generate(TEACHER, SESSION, REQ))
                .isInstanceOfSatisfying(AiQuotaExceededException.class,
                        e -> assertThat(e.getFeature()).isEqualTo(AiFeature.QUIZ_GENERATE));

        verifyNoInteractions(quizAi);
    }
}
