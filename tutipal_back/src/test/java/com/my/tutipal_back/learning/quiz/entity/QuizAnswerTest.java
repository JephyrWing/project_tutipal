package com.my.tutipal_back.learning.quiz.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class QuizAnswerTest {

    @Test
    void preservesFirstSuccessfulAiVerdictAndFeedbackAcrossTeacherChanges() {
        QuizAnswer answer = new QuizAnswer(1L, 2L, null, "답안");
        assertThat(answer.gradingFailed("응답 없음")).isTrue();
        assertThat(answer.recordAiGrade(false, "첫 응답")).isTrue();

        answer.regradeByTeacher(true);
        assertThat(answer.getFeedback()).isNull();
        assertThat(answer.recordAiGrade(true, "다른 응답")).isFalse();
        assertThat(answer.getAiOriginalCorrect()).isFalse();
        assertThat(answer.getAiOriginalFeedback()).isEqualTo("첫 응답");
        assertThat(answer.getCorrect()).isTrue();
        assertThat(answer.getTeacherCorrect()).isTrue();

        answer.regradeByTeacher(false);
        assertThat(answer.getFeedback()).isEqualTo("첫 응답");
        assertThat(answer.getGradedBy()).isEqualTo(GradedBy.TEACHER);
    }

    @Test
    void lateMatchingAiFeedbackIsCompatibleWithTheTeacherVerdict() {
        QuizAnswer answer = new QuizAnswer(1L, 2L, null, "답안");
        answer.regradeByTeacher(false);
        assertThat(answer.gradingFailed("응답 없음")).isFalse();
        assertThat(answer.recordAiGrade(false, "AI 응답")).isTrue();
        assertThat(answer.getAiOriginalFeedback()).isEqualTo("AI 응답");
        assertThat(answer.getFeedback()).isEqualTo("AI 응답");
        assertThat(answer.getGradedBy()).isEqualTo(GradedBy.TEACHER);
    }
}
