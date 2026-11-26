package com.my.tutipal_back.learning.insights.service;

import java.util.List;

import com.my.tutipal_back.learning.insights.repository.QuestionRow;
import com.my.tutipal_back.learning.insights.repository.RawQuestion;

/**
 * PT-87 D3: where the topic analysis reads student questions from. Exactly one bean (today {@link ChatQuestionSource}
 * over chat_questions; PT-106 swaps in a source over the stored conversation history).
 *
 * <p>Contract every implementation keeps:
 * <ul>
 *   <li>only questions of session STUDENT turns (no teacher preview, no personal chat, no variant turn);</li>
 *   <li>only users who are STUDENT participants of the session now (leave / kick removes them);</li>
 *   <li>nothing older than the configured retention (app.insights.questions.retention);</li>
 *   <li>newest first, at most limit rows; {@link QuestionRow#id()} grows with time within a session (the result
 *       cache uses the newest id), and the text stays wrapped in {@link RawQuestion}.</li>
 * </ul>
 */
public interface SessionQuestionSource {

    /** The newest questions of the session first, at most limit rows. */
    List<QuestionRow> recentQuestions(long sessionId, int limit);
}
