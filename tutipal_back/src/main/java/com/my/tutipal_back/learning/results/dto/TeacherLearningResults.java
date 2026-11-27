package com.my.tutipal_back.learning.results.dto;

import java.time.Instant;
import java.util.List;

public record TeacherLearningResults(Counts totals, List<Student> students, List<Question> questions) {

    public record Counts(long unsubmitted, long pending, long correct, long incorrect) {
    }

    public record Student(long userId, String name, Counts counts) {
    }

    public record Question(long quizId, String question, Instant closesAt, Counts counts) {
    }
}
