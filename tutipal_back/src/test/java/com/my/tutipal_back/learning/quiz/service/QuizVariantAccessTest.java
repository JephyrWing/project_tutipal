package com.my.tutipal_back.learning.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.learning.quiz.entity.GradedBy;
import com.my.tutipal_back.learning.quiz.entity.Quiz;
import com.my.tutipal_back.learning.quiz.entity.QuizAnswer;
import com.my.tutipal_back.learning.quiz.entity.QuizType;
import com.my.tutipal_back.learning.quiz.repository.QuizAnswerRepository;
import com.my.tutipal_back.learning.quiz.repository.QuizRepository;
import com.my.tutipal_back.sessions.entity.SessionRole;
import com.my.tutipal_back.sessions.service.StudySessionService;

/**
 * PT-85 AC-12: a student may only ask for a variant of their own wrong answer, in the same session, on a
 * closed quiz. The answer is looked up by (quizId, login userId) only, so another student's answer is unreachable.
 */
class QuizVariantAccessTest {

    private static final long USER = 2L;
    private static final long OTHER_STUDENT = 3L;
    private static final long SESSION = 10L;
    private static final long OTHER_SESSION = 11L;
    private static final long QUIZ = 77L;
    private static final Instant NOW = Instant.parse("2026-11-25T03:00:00Z");

    private final QuizRepository quizzes = mock(QuizRepository.class);
    private final QuizAnswerRepository answers = mock(QuizAnswerRepository.class);
    private final StudySessionService sessions = mock(StudySessionService.class);
    private final QuizVariantAccess access = new QuizVariantAccess(quizzes, answers, sessions,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private Quiz closedChoice;

    @BeforeEach
    void setUp() {
        when(sessions.requireActiveRole(USER, SESSION)).thenReturn(SessionRole.STUDENT);
        closedChoice = new Quiz(SESSION, QuizType.CHOICE, "2 + 3 은?", List.of("4", "5", "6"), 1, null,
                NOW.minusSeconds(60));
        when(quizzes.findById(QUIZ)).thenReturn(Optional.of(closedChoice));
    }

    private static QuizAnswer answer(long userId, Integer choice, String text, Boolean correct, GradedBy by) {
        QuizAnswer answer = new QuizAnswer(QUIZ, userId, choice, text);
        if (by != null) {
            answer.grade(correct, by, "피드백");
        }
        return answer;
    }

    private void myAnswer(QuizAnswer answer) {
        when(answers.findByQuizIdAndUserId(QUIZ, USER)).thenReturn(Optional.ofNullable(answer));
    }

    private void assertRejected(HttpStatus status) {
        assertThatThrownBy(() -> access.requireWrongAnswer(USER, SESSION, QUIZ))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(status));
    }

    @Test
    void returnsQuizForOwnAutoGradedWrongChoice() {
        myAnswer(answer(USER, 0, null, false, GradedBy.AUTO));

        Quiz quiz = access.requireWrongAnswer(USER, SESSION, QUIZ);

        assertThat(quiz).isSameAs(closedChoice);
        verify(sessions).requireActiveRole(USER, SESSION);
    }

    @Test
    void returnsQuizForOwnAiGradedWrongText() {
        Quiz text = new Quiz(SESSION, QuizType.TEXT, "미분의 정의는?", null, null, "순간 변화율", NOW.minusSeconds(1));
        when(quizzes.findById(QUIZ)).thenReturn(Optional.of(text));
        myAnswer(answer(USER, null, "모르겠어요", false, GradedBy.AI));

        assertThat(access.requireWrongAnswer(USER, SESSION, QUIZ)).isSameAs(text);
    }

    @Test
    void aiCorrectRegradedWrongByTeacherIsAllowed() {
        QuizAnswer regraded = answer(USER, null, "답", true, GradedBy.AI);
        regraded.grade(false, GradedBy.TEACHER, "다시 봤어요");
        myAnswer(regraded);

        assertThat(access.requireWrongAnswer(USER, SESSION, QUIZ)).isSameAs(closedChoice);
    }

    @Test
    void wrongRegradedCorrectByTeacherIsConflict() {
        QuizAnswer regraded = answer(USER, null, "답", false, GradedBy.AI);
        regraded.grade(true, GradedBy.TEACHER, "맞았어요");
        myAnswer(regraded);

        assertRejected(HttpStatus.CONFLICT);
    }

    @Test
    void correctAnswerIsConflict() {
        myAnswer(answer(USER, 1, null, true, GradedBy.AUTO));

        assertRejected(HttpStatus.CONFLICT);
    }

    @Test
    void pendingAnswerIsConflict() {
        myAnswer(answer(USER, null, "아직 채점 전", null, null));

        assertRejected(HttpStatus.CONFLICT);
    }

    @Test
    void gradingFailedAnswerIsConflict() {
        QuizAnswer failed = answer(USER, null, "답", false, GradedBy.AI);
        failed.gradingFailed("AI 채점 실패");
        myAnswer(failed);

        assertRejected(HttpStatus.CONFLICT);
    }

    @Test
    void ownMissingAnswerIsConflict() {
        myAnswer(null);

        assertRejected(HttpStatus.CONFLICT);
    }

    @Test
    void openQuizIsConflict() {
        Quiz open = new Quiz(SESSION, QuizType.CHOICE, "열린 퀴즈", List.of("a", "b"), 0, null, NOW.plusSeconds(30));
        when(quizzes.findById(QUIZ)).thenReturn(Optional.of(open));
        myAnswer(answer(USER, 1, null, false, GradedBy.AUTO));

        assertRejected(HttpStatus.CONFLICT);
    }

    @Test
    void quizOfAnotherSessionIsNotFound() {
        Quiz elsewhere = new Quiz(OTHER_SESSION, QuizType.CHOICE, "다른 수업", List.of("a", "b"), 0, null,
                NOW.minusSeconds(60));
        when(quizzes.findById(QUIZ)).thenReturn(Optional.of(elsewhere));
        myAnswer(answer(USER, 1, null, false, GradedBy.AUTO));

        assertRejected(HttpStatus.NOT_FOUND);
    }

    @Test
    void missingQuizIsNotFound() {
        when(quizzes.findById(QUIZ)).thenReturn(Optional.empty());

        assertRejected(HttpStatus.NOT_FOUND);
        verify(answers, never()).findByQuizIdAndUserId(anyLong(), anyLong());
    }

    @Test
    void teacherIsForbidden() {
        when(sessions.requireActiveRole(USER, SESSION)).thenReturn(SessionRole.TEACHER);

        assertRejected(HttpStatus.FORBIDDEN);
        verifyNoInteractions(answers);
    }

    @Test
    void missingSessionIdIsBadRequest() {
        assertThatThrownBy(() -> access.requireWrongAnswer(USER, null, QUIZ))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(quizzes, answers, sessions);
    }

    @Test
    void nonParticipantOrEndedSessionDenialPropagates() {
        when(sessions.requireActiveRole(USER, SESSION)).thenThrow(new ApiException(HttpStatus.GONE, "끝난 수업"));

        assertRejected(HttpStatus.GONE);
        verifyNoInteractions(quizzes, answers);
    }

    @Test
    void anotherStudentsWrongAnswerIsNeverReadEvenIfItExists() {
        when(answers.findByQuizIdAndUserId(QUIZ, OTHER_STUDENT))
                .thenReturn(Optional.of(answer(OTHER_STUDENT, 0, "남의 오답", false, GradedBy.AUTO)));
        myAnswer(null);

        assertRejected(HttpStatus.CONFLICT);

        verify(answers).findByQuizIdAndUserId(QUIZ, USER);
        verify(answers, never()).findByQuizIdAndUserId(QUIZ, OTHER_STUDENT);
        verify(answers, never()).findById(any());
        verify(answers, never()).findByQuizIdOrderByIdAsc(any());
        verify(answers, never()).findByQuizIdIn(any());
        verifyNoMoreInteractions(answers);
    }

    @Test
    void successLooksUpOnlyByQuizIdAndLoginUser() {
        myAnswer(answer(USER, 0, null, false, GradedBy.AUTO));

        access.requireWrongAnswer(USER, SESSION, QUIZ);

        verify(answers).findByQuizIdAndUserId(QUIZ, USER);
        verifyNoMoreInteractions(answers);
    }
}
