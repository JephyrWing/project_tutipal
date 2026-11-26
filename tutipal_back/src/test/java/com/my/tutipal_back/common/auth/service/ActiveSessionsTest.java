package com.my.tutipal_back.common.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpSessionEvent;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

class ActiveSessionsTest {

    private final ActiveSessions sessions = new ActiveSessions();

    private MockHttpSession loggedIn(String id, long userId) {
        MockHttpSession session = new MockHttpSession(null, id);
        var auth = new TestingAuthenticationToken(String.valueOf(userId), null, "ROLE_USER");
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(auth));
        sessions.sessionCreated(new HttpSessionEvent(session));
        return session;
    }

    @Test
    void invalidatesEverySessionOfTheUserAndLeavesOthers() {
        MockHttpSession laptop = loggedIn("a", 7);
        MockHttpSession desktop = loggedIn("b", 7);
        MockHttpSession other = loggedIn("c", 8);

        assertThat(sessions.invalidateUser(7L)).isEqualTo(2);

        assertThat(laptop.isInvalid()).isTrue();
        assertThat(desktop.isInvalid()).isTrue();
        assertThat(other.isInvalid()).isFalse();
    }

    @Test
    void ignoresAnonymousAndUnauthenticatedSessions() {
        MockHttpSession anonymous = new MockHttpSession(null, "anon");
        sessions.sessionCreated(new HttpSessionEvent(anonymous));
        MockHttpSession notAuthenticated = new MockHttpSession(null, "half");
        notAuthenticated.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(new UsernamePasswordAuthenticationToken("7", null)));
        sessions.sessionCreated(new HttpSessionEvent(notAuthenticated));

        assertThat(sessions.invalidateUser(7L)).isZero();
        assertThat(anonymous.isInvalid()).isFalse();
        assertThat(notAuthenticated.isInvalid()).isFalse();
    }

    @Test
    void skipsAlreadyInvalidatedSessionsAndForgetsThem() {
        MockHttpSession gone = loggedIn("gone", 7);
        gone.invalidate(); // 리스너 통지 없이 끝난 세션
        MockHttpSession live = loggedIn("live", 7);

        assertThat(sessions.invalidateUser(7L)).isEqualTo(1);
        assertThat(live.isInvalid()).isTrue();
        assertThat(sessions.size()).isEqualTo(1); // live 는 destroyed 통지 전까지 남고, gone 은 지웠다
    }

    @Test
    void followsSessionIdChangeAndForgetsDestroyedSessions() {
        MockHttpSession session = loggedIn("before-login", 7);
        String newId = session.changeSessionId();
        sessions.sessionIdChanged(new HttpSessionEvent(session), "before-login");
        assertThat(newId).isNotEqualTo("before-login");
        assertThat(sessions.size()).isEqualTo(1);

        sessions.sessionDestroyed(new HttpSessionEvent(session));
        assertThat(sessions.size()).isZero();
        assertThat(sessions.invalidateUser(7L)).isZero();
        assertThat(session.isInvalid()).isFalse();
    }
}
