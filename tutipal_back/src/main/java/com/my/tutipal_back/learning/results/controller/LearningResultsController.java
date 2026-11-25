package com.my.tutipal_back.learning.results.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.my.tutipal_back.learning.results.dto.LearningResultsPage;
import com.my.tutipal_back.learning.results.service.LearningResultsService;

@RestController
public class LearningResultsController {
    private final LearningResultsService service;
    public LearningResultsController(LearningResultsService service) { this.service = service; }
    @GetMapping("/api/learning-results")
    public LearningResultsPage history(@AuthenticationPrincipal OAuth2User me,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size,
            @RequestParam(required=false) Long sessionId, @RequestParam(defaultValue="ALL") String verdict) {
        return service.history(Long.parseLong(me.getName()),page,size,sessionId,verdict);
    }
}
