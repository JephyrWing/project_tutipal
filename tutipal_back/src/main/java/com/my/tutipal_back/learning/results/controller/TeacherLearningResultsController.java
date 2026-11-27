package com.my.tutipal_back.learning.results.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.my.tutipal_back.learning.results.dto.TeacherLearningResults;
import com.my.tutipal_back.learning.results.service.TeacherLearningResultsService;

@RestController
public class TeacherLearningResultsController {
    private final TeacherLearningResultsService service;

    public TeacherLearningResultsController(TeacherLearningResultsService service) {
        this.service = service;
    }

    @GetMapping("/api/sessions/{sessionId}/learning-results")
    public TeacherLearningResults results(@AuthenticationPrincipal OAuth2User me, @PathVariable long sessionId) {
        return service.results(Long.parseLong(me.getName()), sessionId);
    }
}
