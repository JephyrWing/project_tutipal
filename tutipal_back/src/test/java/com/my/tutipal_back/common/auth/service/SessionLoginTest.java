package com.my.tutipal_back.common.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpSessionEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.util.ReflectionTestUtils;

import com.my.tutipal_back.users.entity.User;

/** 한 계정당 기기 1대: 새 로그인이 같은 계정의 이전 기기 세션을 끊는다 (PT-13, 2026-11-26). */
class SessionLoginTest {

    private final ActiveSessions activeSessions = new ActiveSessions();
    private final SessionLogin sessionLogin = new SessionLogin(activeSessions);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpSession loggedInElsewhere(String id, long userId) {
        MockHttpSession session = new MockHttpSession(null, id);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(new TestingAuthenticationToken(String.valueOf(userId), null, "ROLE_USER")));
        activeSessions.sessionCreated(new HttpSessionEvent(session));
        return session;
    }

    private static User user(long id) {
        User user = new User("google", "g-" + id, "사용자" + id);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    void newLoginEndsTheSameUsersOtherDevicesOnly() {
        MockHttpSession oldDevice = loggedInElsewhere("old-device", 7);
        MockHttpSession otherUser = loggedInElsewhere("other-user", 8);
        MockHttpServletRequest request = new MockHttpServletRequest();

        sessionLogin.login(user(7), request, new MockHttpServletResponse());

        assertThat(oldDevice.isInvalid()).isTrue();
        assertThat(otherUser.isInvalid()).isFalse();
        MockHttpSession current = (MockHttpSession) request.getSession(false);
        assertThat(current).isNotNull();
        assertThat(current.isInvalid()).isFalse();
        Object saved = current.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(saved).isInstanceOf(SecurityContext.class);
        assertThat(((SecurityContext) saved).getAuthentication().getName()).isEqualTo("7");
    }

    @Test
    void reLoginOnTheSameDeviceKeepsTheNewSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession existing = new MockHttpSession(null, "same-device");
        request.setSession(existing);
        activeSessions.sessionCreated(new HttpSessionEvent(existing));

        // 로그인 중 changeSessionId 가 일어나도(리스너 통지 전) 현재 세션은 끊지 않는다
        sessionLogin.login(user(7), request, new MockHttpServletResponse());

        MockHttpSession current = (MockHttpSession) request.getSession(false);
        assertThat(current).isSameAs(existing);
        assertThat(current.getId()).isNotEqualTo("same-device");
        assertThat(current.isInvalid()).isFalse();
    }
}
