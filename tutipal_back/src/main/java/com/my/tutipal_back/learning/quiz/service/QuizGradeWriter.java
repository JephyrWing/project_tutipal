package com.my.tutipal_back.learning.quiz.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.my.tutipal_back.learning.quiz.repository.QuizAnswerRepository;

@Service
public class QuizGradeWriter {
    private final QuizAnswerRepository answers;

    public QuizGradeWriter(QuizAnswerRepository answers) {
        this.answers = answers;
    }

    /** AI 응답을 기다리는 동안 DB 잠금을 잡지 않고, 반영할 때 교사 재판정과 같은 답안 행을 잠근다. */
    @Transactional
    public boolean saveAiResult(long answerId, QuizAi.Grade grade) {
        return answers.findForGrading(answerId).map(answer -> grade == null
                ? answer.gradingFailed(QuizService.AI_FAILED)
                : answer.recordAiGrade(grade.correct(), grade.feedback())).orElse(false);
    }
}
