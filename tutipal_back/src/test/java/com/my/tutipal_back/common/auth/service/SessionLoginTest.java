package com.my.tutipal_back.common.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.http.HttpSessionEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.util.ReflectionTestUtils;

import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.users.entity.User;
import com.my.tutipal_back.users.repository.AccountLocks;

/**
 * 한 계정당 기기 1대: 새 로그인이 같은 계정의 이전 기기 세션을 끊는다 (PT-13, 2026-11-26).
 * 회원 탈퇴와의 경합(Codex P1-2, PT-55): 계정별 잠금 안에서 계정이 활성인지 다시 확인한다.
 */
class SessionLoginTest {

    /** DB 상태 대신: true = 활성, false = 탈퇴가 커밋됨. */
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AccountLocks accounts = accounts(active);
    private final ActiveSessions activeSessions = new ActiveSessions();
    private final SessionLogin sessionLogin = new SessionLogin(activeSessions, accounts);

    private static AccountLocks accounts(AtomicBoolean active) {
        AccountLocks accounts = mock(AccountLocks.class);
        when(accounts.isActive(anyLong())).thenAnswer(call -> active.get());
        return accounts;
    }

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
    void concurrentLoginsOfTheSameUserLeaveExactlyOneSession() throws Exception {
        for (int round = 0; round < 50; round++) {
            ActiveSessions registry = new ActiveSessions();
            SessionLogin login = new SessionLogin(registry, accounts);
            MockHttpServletRequest first = requestWithRegisteredSession(registry, "a" + round);
            MockHttpServletRequest second = requestWithRegisteredSession(registry, "b" + round);
            MockHttpSession firstSession = (MockHttpSession) first.getSession(false);
            MockHttpSession secondSession = (MockHttpSession) second.getSession(false);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> f1 = pool.submit(() -> { await(start); login.login(user(7), first, new MockHttpServletResponse()); });
                Future<?> f2 = pool.submit(() -> { await(start); login.login(user(7), second, new MockHttpServletResponse()); });
                start.countDown();
                f1.get(5, TimeUnit.SECONDS);
                f2.get(5, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
            boolean firstAlive = !firstSession.isInvalid();
            boolean secondAlive = !secondSession.isInvalid();
            assertThat(firstAlive ^ secondAlive).as("round %d: exactly one device stays logged in", round).isTrue();
        }
    }

    private static MockHttpServletRequest requestWithRegisteredSession(ActiveSessions registry, String id) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession(null, id);
        request.setSession(session);
        registry.sessionCreated(new HttpSessionEvent(session));
        return request;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

    // ------------------------------------------------------------------ withdrawal race (Codex P1-2)

    /** WithdrawalService after commit: invalidate inside the same per-account lock. */
    private void withdrawalCommittedAndInvalidated(long userId) {
        active.set(false);
        activeSessions.exclusiveForUser(userId, () -> activeSessions.invalidateUser(userId));
    }

    private static MockHttpServletRequest registeredRequest(ActiveSessions registry, String id) {
        return requestWithRegisteredSession(registry, id);
    }

    @Test
    void loginThatSavedItsSessionFirstIsEndedByTheWithdrawalInvalidation() {
        MockHttpServletRequest request = registeredRequest(activeSessions, "login-first");

        sessionLogin.login(user(7), request, new MockHttpServletResponse());
        MockHttpSession session = (MockHttpSession) request.getSession(false);
        assertThat(session.isInvalid()).isFalse();
        withdrawalCommittedAndInvalidated(7);

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void loginWaitingBehindTheWithdrawalInvalidationIsRefusedWith401() throws Exception {
        MockHttpServletRequest request = registeredRequest(activeSessions, "login-second");
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> withdrawal = pool.submit(() -> activeSessions.exclusiveForUser(7L, () -> {
                active.set(false);   // already committed
                activeSessions.invalidateUser(7L);
                holding.countDown();
                await(release);
            }));
            assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> login = pool.submit(() -> sessionLogin.login(user(7), request, new MockHttpServletResponse()));
            Thread.sleep(200);
            assertThat(login.isDone()).as("login waits for the per-account lock").isFalse();
            release.countDown();
            withdrawal.get(5, TimeUnit.SECONDS);

            assertThatThrownBy(() -> login.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e.getCause()).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        } finally {
            pool.shutdownNow();
        }
        MockHttpSession session = (MockHttpSession) request.getSession(false);
        assertThat(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .as("no login saved").isNull();
    }

    @Test
    void loginDuringAnUncommittedWithdrawalIsEndedOnceItCommits() {
        MockHttpServletRequest request = registeredRequest(activeSessions, "login-during");

        // withdrawal transaction still open: the account still reads as active
        sessionLogin.login(user(7), request, new MockHttpServletResponse());
        MockHttpSession session = (MockHttpSession) request.getSession(false);
        assertThat(session.isInvalid()).isFalse();
        withdrawalCommittedAndInvalidated(7);

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void withdrawnAccountIsRefusedWithoutEndingOtherSessionsOrKeepingAContext() {
        MockHttpSession otherDevice = loggedInElsewhere("other-device", 7);
        active.set(false);
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> sessionLogin.login(user(7), request, new MockHttpServletResponse()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getSession(false).getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .isNull();
        assertThat(otherDevice.isInvalid()).as("withdrawal itself ends it, not the refused login").isFalse();
    }
}
