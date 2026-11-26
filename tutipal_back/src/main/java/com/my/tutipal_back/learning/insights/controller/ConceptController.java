package com.my.tutipal_back.learning.insights.controller;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.my.tutipal_back.learning.insights.dto.ConceptNamesRequest;
import com.my.tutipal_back.learning.insights.dto.ConceptView;
import com.my.tutipal_back.learning.insights.dto.QuizConceptView;
import com.my.tutipal_back.learning.insights.service.ConceptTagService;

/** PT-87: concept tags (teacher of the session only). */
@RestController
@RequestMapping("/api")
public class ConceptController {

    private final ConceptTagService service;

    public ConceptController(ConceptTagService service) {
        this.service = service;
    }

    @GetMapping("/sessions/{sessionId}/concepts")
    public List<ConceptView> concepts(@AuthenticationPrincipal OAuth2User me, @PathVariable Long sessionId) {
        return service.sessionConcepts(userId(me), sessionId);
    }

    /** quizId -> tags of that quiz (untagged quizzes are absent). */
    @GetMapping("/sessions/{sessionId}/quiz-concepts")
    public Map<Long, List<QuizConceptView>> quizConcepts(@AuthenticationPrincipal OAuth2User me,
                                                         @PathVariable Long sessionId) {
        return service.quizConcepts(userId(me), sessionId);
    }

    /** Replaces the whole tag set of the quiz (idempotent); returns the stored tags. */
    @PutMapping("/quizzes/{quizId}/concepts")
    public List<QuizConceptView> replace(@AuthenticationPrincipal OAuth2User me, @PathVariable Long quizId,
                                         @Valid @RequestBody ConceptNamesRequest request) {
        return service.replace(userId(me), quizId, request);
    }

    private static Long userId(OAuth2User me) {
        return Long.valueOf(me.getName());
    }
}
