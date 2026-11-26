package com.my.tutipal_back.learning.insights.dto;

/** PT-87: one concept tag on a quiz. source = TEACHER | AI (AI = a suggestion the teacher accepted). */
public record QuizConceptView(Long conceptId, String name, String source) {
}
