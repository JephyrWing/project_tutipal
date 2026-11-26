package com.my.tutipal_back.common.auth.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;

import com.my.tutipal_back.users.entity.User;
import com.my.tutipal_back.users.service.SocialOAuth2UserService;

/**
 * 소셜 로그인 화면을 거치지 않고 세션 쿠키 로그인을 만든다 (앱 딥링크 교환 · 개발용 로그인).
 * 세션에 넣는 모양은 소셜 로그인과 같다 (principal.getName() == users.id).
 * 한 계정은 한 기기에서만 로그인한다: 새로 로그인하면 같은 계정의 다른 세션을 무효화한다.
 */
@Component
public class SessionLogin {

    private final HttpSessionSecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();
    private final ActiveSessions activeSessions;

    public SessionLogin(ActiveSessions activeSessions) {
        this.activeSessions = activeSessions;
    }

    public void login(User user, HttpServletRequest request, HttpServletResponse response) {
        OAuth2User principal = SocialOAuth2UserService.principalOf(user);
        request.getSession(true);
        request.changeSessionId();   // 로그인 전 세션 번호를 재사용하지 않게 (세션 고정 방지)
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities()));
        SecurityContextHolder.setContext(context);
        // 한 계정당 기기 1대: 새 로그인이 이기고 먼저 로그인한 기기의 세션은 끊는다 (PT-13, 2026-11-26).
        // 인증 저장과 다른 세션 무효화를 계정별 잠금 안에서 함께 해야 동시 로그인 두 건이 서로를 끊지 않는다.
        activeSessions.exclusiveForUser(user.getId(), () -> {
            contextRepository.saveContext(context, request, response);
            activeSessions.invalidateUserExcept(user.getId(), request.getSession());
        });
    }
}
