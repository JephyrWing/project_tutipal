package com.my.tutipal_back.learning.quiz.dto;

import java.util.List;

import com.my.tutipal_back.learning.quiz.entity.Quiz;
import com.my.tutipal_back.learning.quiz.entity.QuizType;

/**
 * PT-85: the original question handed to the tutor's variant tool, copied out of the Quiz entity so the
 * tutor package never holds a JPA entity. Student answers and feedback are deliberately not part of it (AC-13).
 */
public record VariantOriginal(QuizType type, String question, List<String> choices, Integer answerIndex,
                              String modelAnswer) {

    public VariantOriginal {
        choices = choices == null ? null : List.copyOf(choices);
    }

    public static VariantOriginal of(Quiz quiz) {
        return new VariantOriginal(quiz.getType(), quiz.getQuestion(), quiz.getChoices(), quiz.getAnswerIndex(),
                quiz.getModelAnswer());
    }
}
