package com.my.tutipal_back.learning.insights.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.my.tutipal_back.learning.insights.repository.ChatQuestionStore;
import com.my.tutipal_back.learning.tutor.service.StudentQuestionSink;

/**
 * PT-87 AC-6: stores a session student's question for the teacher's concept insights.
 * Trims, cuts to 1000 code points (the column limit), skips blank text. The INSERT itself checks that the user is a
 * STUDENT participant of an open session. Never throws to the chat and never logs the text (exception class only).
 */
@Component
public class ChatQuestionRecorder implements StudentQuestionSink {

    private static final Logger log = LoggerFactory.getLogger(ChatQuestionRecorder.class);
    static final int MAX_CODE_POINTS = 1000;

    private final ChatQuestionStore store;

    public ChatQuestionRecorder(ChatQuestionStore store) {
        this.store = store;
    }

    /**
     * No @Transactional on purpose: the single INSERT ... SELECT is atomic in autocommit, and a failure caught inside a
     * proxy transaction would make the commit itself throw (PostgreSQL aborted the transaction).
     */
    @Override
    public void record(Long userId, Long sessionId, String question) {
        if (userId == null || sessionId == null || question == null) {
            return;
        }
        String text = question.strip();
        if (text.isEmpty()) {
            return;
        }
        if (text.codePointCount(0, text.length()) > MAX_CODE_POINTS) {
            text = text.substring(0, text.offsetByCodePoints(0, MAX_CODE_POINTS));
        }
        try {
            store.insertIfActiveStudent(sessionId, userId, text);
        } catch (RuntimeException e) {
            log.warn("student question not stored: {}", e.getClass().getSimpleName());
        }
    }
}
