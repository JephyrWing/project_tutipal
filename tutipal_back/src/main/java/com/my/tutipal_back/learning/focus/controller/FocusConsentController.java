package com.my.tutipal_back.learning.focus.controller;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.my.tutipal_back.learning.focus.dto.FocusDtos.ConsentRequest;
import com.my.tutipal_back.learning.focus.dto.FocusDtos.ConsentResponse;
import com.my.tutipal_back.learning.focus.service.FocusConsentService;

/** PT-90 the logged-in user's consent to focus data storage. DELETE withdraws it and deletes the stored data. */
@RestController
@RequestMapping("/api/focus/consent")
public class FocusConsentController {

    private final FocusConsentService consents;

    public FocusConsentController(FocusConsentService consents) {
        this.consents = consents;
    }

    @GetMapping
    public ConsentResponse current(@AuthenticationPrincipal OAuth2User me) {
        return consents.current(userId(me));
    }

    @PutMapping
    public ConsentResponse give(@AuthenticationPrincipal OAuth2User me, @Valid @RequestBody ConsentRequest request) {
        return consents.give(userId(me), request.version());
    }

    @DeleteMapping
    public ResponseEntity<Void> withdraw(@AuthenticationPrincipal OAuth2User me) {
        consents.withdraw(userId(me));
        return ResponseEntity.noContent().build();
    }

    private static long userId(OAuth2User me) {
        return Long.parseLong(me.getName());
    }
}
