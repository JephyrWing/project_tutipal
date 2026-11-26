package com.my.tutipal_back.learning.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.learning.quiz.dto.VariantOriginal;
import com.my.tutipal_back.learning.quiz.entity.GradedBy;
import com.my.tutipal_back.learning.quiz.entity.Quiz;
import com.my.tutipal_back.learning.quiz.entity.QuizAnswer;
import com.my.tutipal_back.learning.quiz.entity.QuizType;
import com.my.tutipal_back.learning.quiz.repository.QuizAnswerRepository;
import com.my.tutipal_back.learning.quiz.repository.QuizRepository;
import com.my.tutipal_back.sessions.entity.SessionRole;
import com.my.tutipal_back.sessions.service.StudySessionService;

/** PT-85 Round 1 review S5: the tutor gets a value copy of the original question, never the Quiz entity. */
class QuizVariantOriginalTest {

    private static final long USER = 2L;
    private static final long SESSION = 10L;
    private static final long QUIZ = 77L;
    private static final Instant NOW = Instant.parse("2026-11-25T03:00:00Z");

    private final QuizRepository quizzes = mock(QuizRepository.class);
    private final QuizAnswerRepository answers = mock(QuizAnswerRepository.class);
    private final StudySessionService sessions = mock(StudySessionService.class);
    private final QuizVariantAccess access = new QuizVariantAccess(quizzes, answers, sessions,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(sessions.requireActiveRole(USER, SESSION)).thenReturn(SessionRole.STUDENT);
    }

    private void wrongAnswerTo(Quiz quiz) {
        when(quizzes.findById(QUIZ)).thenReturn(Optional.of(quiz));
        QuizAnswer mine = new QuizAnswer(QUIZ, USER, 0, "STUDENT-ANSWER");
        mine.grade(false, GradedBy.AUTO, "FEEDBACK");
        when(answers.findByQuizIdAndUserId(QUIZ, USER)).thenReturn(Optional.of(mine));
    }

    @Test
    void choiceQuestionIsCopiedWithChoicesAndAnswerIndex() {
        wrongAnswerTo(new Quiz(SESSION, QuizType.CHOICE, "2 + 3 은?", List.of("4", "5", "6"), 1, null,
                NOW.minusSeconds(60)));

        assertThat(access.requireVariantOriginal(USER, SESSION, QUIZ))
                .isEqualTo(new VariantOriginal(QuizType.CHOICE, "2 + 3 은?", List.of("4", "5", "6"), 1, null));
    }

    @Test
    void textQuestionIsCopiedWithModelAnswer() {
        wrongAnswerTo(new Quiz(SESSION, QuizType.TEXT, "미분의 정의는?", null, null, "순간 변화율", NOW.minusSeconds(60)));

        assertThat(access.requireVariantOriginal(USER, SESSION, QUIZ))
                .isEqualTo(new VariantOriginal(QuizType.TEXT, "미분의 정의는?", null, null, "순간 변화율"));
    }

    @Test
    void sameRulesAsTheEntityCheck() {
        when(sessions.requireActiveRole(USER, SESSION)).thenReturn(SessionRole.TEACHER);

        assertThatThrownBy(() -> access.requireVariantOriginal(USER, SESSION, QUIZ))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void choicesAreCopiedSoTheEntityListCannotLeakChanges() {
        List<String> choices = new ArrayList<>(List.of("4", "5"));
        VariantOriginal original = new VariantOriginal(QuizType.CHOICE, "q", choices, 0, null);
        choices.add("6");

        assertThat(original.choices()).containsExactly("4", "5");
    }
}
