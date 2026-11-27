package com.my.tutipal_back.moderation;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.my.tutipal_back.moderation.ContentModerationDtos.Action;
import com.my.tutipal_back.moderation.ContentModerationDtos.ItemPage;
import com.my.tutipal_back.moderation.ContentModerationDtos.Kind;
import com.my.tutipal_back.moderation.ContentModerationDtos.VisibilityRequest;

@RestController
@RequestMapping("/api/admin/content")
class ContentModerationController {

    private final ContentModerationService service;

    ContentModerationController(ContentModerationService service) {
        this.service = service;
    }

    @GetMapping
    public ItemPage list(@AuthenticationPrincipal OAuth2User me, @RequestParam Kind kind,
                         @RequestParam(required = false) Long before) {
        return service.list(Long.parseLong(me.getName()), kind, before);
    }

    @GetMapping("/{kind}/{id}/history")
    public List<Action> history(@AuthenticationPrincipal OAuth2User me, @PathVariable Kind kind, @PathVariable long id) {
        return service.history(Long.parseLong(me.getName()), kind, id);
    }

    @PutMapping("/{kind}/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setVisibility(@AuthenticationPrincipal OAuth2User me, @PathVariable Kind kind, @PathVariable long id,
                              @Valid @RequestBody VisibilityRequest request) {
        service.setVisibility(Long.parseLong(me.getName()), kind, id, request);
    }
}
