package com.my.tutipal_back.learning.results.dto;

import java.time.Instant;
import java.util.List;

/** Explicit allow-list: never serialize quiz entities or grading feedback. */
public record LearningResultsPage(List<Item> items, List<SessionOption> sessions, long totalCount,
                                  int page, int size, long totalPages) {
    public record Item(long answerId, long quizId, long sessionId, String sessionTitle, String topic,
                       String type, String question, List<String> choices, Integer choiceIndex,
                       String text, Instant submittedAt, String verdict) { }
    public record SessionOption(long id, String title) { }
}
