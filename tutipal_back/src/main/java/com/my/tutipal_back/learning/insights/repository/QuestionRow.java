package com.my.tutipal_back.learning.insights.repository;

/**
 * PT-87: one stored question as the analysis reads it. userId stays in Spring memory (never sent to FastAPI and
 * never put in a response); the text is wrapped so it cannot be printed or serialized by accident.
 */
public record QuestionRow(long id, long userId, RawQuestion text) {
}
