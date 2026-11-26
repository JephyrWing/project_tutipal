package com.my.tutipal_back.learning.tutor.service;

/**
 * PT-87: where TutorService hands a student's session question (the last user message of a STUDENT turn).
 * Owned by tutor so that tutor does not depend on the insights feature; the insights ChatQuestionRecorder implements
 * it. {@link StudentQuestionSinkConfig} registers {@link #NONE} only when no other implementation exists.
 *
 * <p>Implementations should not throw for normal failures and must never log the question text.
 */
public interface StudentQuestionSink {

    /** Does nothing (no insights feature in this context). */
    StudentQuestionSink NONE = (userId, sessionId, question) -> { };

    void record(Long userId, Long sessionId, String question);
}
