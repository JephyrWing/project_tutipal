package com.my.tutipal_back.learning.insights.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.my.tutipal_back.learning.insights.dto.ConfusionsResponse;
import com.my.tutipal_back.learning.insights.dto.MasteryResponse;
import com.my.tutipal_back.learning.insights.service.MasteryService;
import com.my.tutipal_back.learning.insights.service.TopicAnalysisService;

/** PT-87: teacher dashboard data (teacher of the session only; ended sessions can still be viewed). */
@RestController
@RequestMapping("/api/sessions/{sessionId}/insights")
public class InsightsController {

    private final TopicAnalysisService topics;
    private final MasteryService mastery;

    public InsightsController(TopicAnalysisService topics, MasteryService mastery) {
        this.topics = topics;
        this.mastery = mastery;
    }

    @GetMapping("/mastery")
    public MasteryResponse mastery(@AuthenticationPrincipal OAuth2User me, @PathVariable Long sessionId) {
        return mastery.mastery(userId(me), sessionId);
    }

    /** POST because it may call the AI and use the INSIGHTS quota; the screen calls it only on "분석하기". */
    @PostMapping("/confusions")
    public ConfusionsResponse confusions(@AuthenticationPrincipal OAuth2User me, @PathVariable Long sessionId) {
        return topics.analyze(userId(me), sessionId);
    }

    private static Long userId(OAuth2User me) {
        return Long.valueOf(me.getName());
    }
}
