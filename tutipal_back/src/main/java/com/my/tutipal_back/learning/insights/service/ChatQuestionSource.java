package com.my.tutipal_back.learning.insights.service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.my.tutipal_back.learning.insights.repository.ChatQuestionStore;
import com.my.tutipal_back.learning.insights.repository.QuestionRow;

/**
 * PT-87: the current {@link SessionQuestionSource}, over chat_questions. PT-106 migration = replace this one bean
 * with a source over the stored conversation history (then ChatQuestionRecorder and chat_questions can go).
 *
 * <p>Reads only rows younger than app.insights.questions.retention (same key and clock pattern as
 * QuestionRetentionJob), so rows that expired since the last purge run are not analyzed.
 */
@Component
public class ChatQuestionSource implements SessionQuestionSource {

    private final ChatQuestionStore store;
    private final Duration retention;
    private final Clock clock;

    @Autowired
    public ChatQuestionSource(ChatQuestionStore store,
                              @Value("${app.insights.questions.retention}") Duration retention) {
        this(store, retention, Clock.systemUTC());
    }

    ChatQuestionSource(ChatQuestionStore store, Duration retention, Clock clock) {
        if (retention == null || retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("app.insights.questions.retention 은 0보다 커야 해요");
        }
        this.store = store;
        this.retention = retention;
        this.clock = clock;
    }

    @Override
    public List<QuestionRow> recentQuestions(long sessionId, int limit) {
        return store.recentQuestions(sessionId, limit, clock.instant().minus(retention));
    }
}
