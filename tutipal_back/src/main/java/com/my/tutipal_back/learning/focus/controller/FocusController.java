package com.my.tutipal_back.learning.focus.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.my.tutipal_back.learning.focus.dto.FocusDtos.FocusStatusResponse;
import com.my.tutipal_back.learning.focus.dto.FocusDtos.FocusSubmitRequest;
import com.my.tutipal_back.learning.focus.dto.FocusDtos.StudentFocus;
import com.my.tutipal_back.learning.focus.service.FocusService;

/**
 * PT-90 focus check of a class. The user id always comes from the login (a userId in the body is ignored).
 * POST: the student's desktop app sends 1..15 minutes; GET: the class teacher's badges; GET /me: the student's own
 * state.
 */
@RestController
@RequestMapping("/api/sessions/{id}/focus")
public class FocusController {

    private final FocusService focus;

    public FocusController(FocusService focus) {
        this.focus = focus;
    }

    @PostMapping
    public FocusStatusResponse submit(@AuthenticationPrincipal OAuth2User me, @PathVariable long id,
                                      @Valid @RequestBody FocusSubmitRequest request) {
        return focus.submit(userId(me), id, request);
    }

    @GetMapping
    public List<StudentFocus> list(@AuthenticationPrincipal OAuth2User me, @PathVariable long id) {
        return focus.list(userId(me), id);
    }

    @GetMapping("/me")
    public FocusStatusResponse me(@AuthenticationPrincipal OAuth2User me, @PathVariable long id) {
        return focus.me(userId(me), id);
    }

    private static long userId(OAuth2User me) {
        return Long.parseLong(me.getName());
    }
}
