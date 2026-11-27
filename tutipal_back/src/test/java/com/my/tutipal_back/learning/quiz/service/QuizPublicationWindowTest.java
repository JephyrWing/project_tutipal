package com.my.tutipal_back.learning.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.my.tutipal_back.ai.quota.MutableClock;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.common.realtime.SessionEvents;
import com.my.tutipal_back.learning.quiz.dto.QuizDtos.AnswerRequest;
import com.my.tutipal_back.learning.quiz.dto.QuizDtos.CreateQuizRequest;
import com.my.tutipal_back.learning.quiz.entity.Quiz;
import com.my.tutipal_back.learning.quiz.entity.QuizType;
import com.my.tutipal_back.learning.quiz.repository.QuizAnswerRepository;
import com.my.tutipal_back.learning.quiz.repository.QuizRepository;
import com.my.tutipal_back.sessions.entity.SessionRole;
import com.my.tutipal_back.sessions.service.StudySessionService;

class QuizPublicationWindowTest {
    private static final long SESSION = 10L;
    private static final long TEACHER = 20L;
    private static final long STUDENT = 30L;
    private static final long OLD_QUIZ = 40L;
    private static final long NEW_QUIZ = 41L;
    private static final Instant CLOSE = Instant.parse("2026-11-27T08:00:00Z");
    private static final List<String> CHOICES = List.of("1", "2");

    private final QuizRepository quizzes = mock(QuizRepository.class);
    private final QuizAnswerRepository answers = mock(QuizAnswerRepository.class);
    private final StudySessionService sessions = mock(StudySessionService.class);
    private final SessionEvents events = mock(SessionEvents.class);
    private final MutableClock clock = new MutableClock(CLOSE);
    private final QuizService service = new QuizService(quizzes, answers, sessions, null, null, events, null,
            null, null, clock);

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 1})
    void submissionEndsAtTheSameInstantThatPublicationUnlocks(long nanosFromBoundary) {
        clock.set(CLOSE.plusSeconds(3).plusNanos(nanosFromBoundary));
        var previous = new Quiz(SESSION, QuizType.CHOICE, "이전 문항", CHOICES, 1, null, CLOSE);
        when(quizzes.findById(OLD_QUIZ)).thenReturn(Optional.of(previous));
        when(sessions.requireActiveRole(STUDENT, SESSION)).thenReturn(SessionRole.STUDENT);
        when(quizzes.existsBySessionIdAndClosesAtAfter(any(), any()))
                .thenAnswer(call -> CLOSE.isAfter(call.getArgument(1, Instant.class)));
        var submission = new AnswerRequest(1, null);
        var next = new CreateQuizRequest(QuizType.CHOICE, "새 문항", CHOICES, 1, null, 60);

        if (nanosFromBoundary < 0) {
            assertThat(service.answer(STUDENT, OLD_QUIZ, submission).correct()).isTrue();
            assertThatThrownBy(() -> service.create(TEACHER, SESSION, next))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.LOCKED));
            verify(quizzes, never()).save(any());
            verify(events, never()).toSession(any(), any(), any());
        } else {
            assertThatThrownBy(() -> service.answer(STUDENT, OLD_QUIZ, submission))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.GONE));
            var published = new Quiz(SESSION, QuizType.CHOICE, next.question(), CHOICES, 1, null,
                    clock.instant().plusSeconds(60));
            ReflectionTestUtils.setField(published, "id", NEW_QUIZ);
            when(quizzes.save(any())).thenReturn(published);
            when(quizzes.findBySessionIdOrderByIdAsc(SESSION)).thenReturn(List.of(published));
            assertThat(service.create(TEACHER, SESSION, next).id()).isEqualTo(NEW_QUIZ);
            verify(events).toSession(SESSION, "quiz", NEW_QUIZ);
        }

        var order = inOrder(sessions, quizzes);
        order.verify(sessions).requireActiveTeacherForQuizWrite(TEACHER, SESSION);
        order.verify(quizzes).existsBySessionIdAndClosesAtAfter(SESSION,
                clock.instant().truncatedTo(ChronoUnit.MICROS).minusSeconds(3));
    }
}
