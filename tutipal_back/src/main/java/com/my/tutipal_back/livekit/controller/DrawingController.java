package com.my.tutipal_back.livekit.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.my.tutipal_back.livekit.service.HelpDrawingService;

@RestController
public class DrawingController {

    private final HelpDrawingService drawings;

    public DrawingController(HelpDrawingService drawings) {
        this.drawings = drawings;
    }

    public record Drawing(Map<String, Object> message) {}

    @PostMapping("/api/help/{id}/draw")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void draw(@AuthenticationPrincipal OAuth2User me, @PathVariable Long id, @RequestBody Drawing body) {
        drawings.publish(Long.valueOf(me.getName()), id, body.message());
    }
}
