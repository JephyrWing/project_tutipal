package com.my.tutipal_back.learning.insights.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * PT-87 PUT /api/quizzes/{quizId}/concepts: the full tag set of the quiz (replaces what was there).
 * Per-name rules (1..30 characters after trimming, case/space duplicates merged) and the 50-per-session limit
 * are checked in ConceptTagService because they need the trimmed value and the stored concepts.
 * source is optional and defaults to TEACHER. Each raw name is capped at 200 characters before any processing
 * (generous for surrounding whitespace; the real 30-character rule is applied after trimming).
 */
public record ConceptNamesRequest(
        @NotNull @Size(max = 3) List<@Size(max = 200) String> names,
        @Pattern(regexp = "TEACHER|AI") String source) {
}
