package com.my.tutipal_back.users.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.my.tutipal_back.users.dto.UserResponse;
import com.my.tutipal_back.users.dto.UpdateNameRequest;
import com.my.tutipal_back.users.service.UserService;
import com.my.tutipal_back.users.service.WithdrawalService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final WithdrawalService withdrawalService;

    public UserController(UserService userService, WithdrawalService withdrawalService) {
        this.userService = userService;
        this.withdrawalService = withdrawalService;
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal OAuth2User principal) {
        return userService.getMe(Long.valueOf(principal.getName()));
    }

    @PatchMapping("/me/name")
    public UserResponse updateName(@AuthenticationPrincipal OAuth2User principal,
            @RequestBody UpdateNameRequest request) {
        return userService.updateName(Long.valueOf(principal.getName()), request.name());
    }

    /** 회원 탈퇴 (FR-MY-02). 기록을 지운 뒤(커밋 완료) 이 사용자의 모든 로그인 세션을 끊는다. */
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@AuthenticationPrincipal OAuth2User principal, HttpServletRequest request) {
        withdrawalService.withdraw(Long.valueOf(principal.getName()));
        HttpSession session = request.getSession(false);
        if (session != null) {
            try {
                session.invalidate();
            } catch (IllegalStateException alreadyEndedByWithdrawal) {
                // 탈퇴 처리(커밋 후)가 이 세션도 이미 끊었다.
            }
        }
        SecurityContextHolder.clearContext();
    }
}
