package com.my.tutipal_back.sessions.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.sessions.controller.StudySessionController;
import com.my.tutipal_back.sessions.dto.SessionResponse;
import com.my.tutipal_back.sessions.service.InviteCodeJoinService.RetryLater;

class InviteCodeJoinServiceTest {

    private static final Instant START = Instant.parse("2026-11-25T02:00:00Z");
    private static final String UNKNOWN_CODE = "MISS01";
    private static final String CLOSED_CODE = "CLOSE1";
    private static final String ACTIVE_CODE = "ROOM01";

    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private final StudySessionService sessions = mock(StudySessionService.class);
    private final Clock clock = mock(Clock.class);
    private final InviteCodeJoinService joinService = new InviteCodeJoinService(sessions, clock);

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenAnswer(invocation -> now.get());
        when(sessions.join(7L, UNKNOWN_CODE)).thenThrow(new ApiException(HttpStatus.NOT_FOUND, "코드에 맞는 방이 없어요"));
        when(sessions.join(7L, CLOSED_CODE)).thenThrow(new ApiException(HttpStatus.GONE, "이미 닫힌 방이에요"));
    }

    @Test
    void failureWindowPreservesErrorsAndExpiresWithoutExtendingOnBlockedAttempts() {
        SessionResponse joined = mock(SessionResponse.class);
        when(sessions.join(7L, ACTIVE_CODE)).thenReturn(joined);
        when(sessions.join(8L, ACTIVE_CODE)).thenReturn(joined);

        expectFailure(UNKNOWN_CODE, HttpStatus.NOT_FOUND);
        assertSame(joined, joinService.join(7L, ACTIVE_CODE));
        expectFailure(CLOSED_CODE, HttpStatus.GONE);
        for (int i = 0; i < 3; i++) {
            expectFailure(UNKNOWN_CODE, HttpStatus.NOT_FOUND);
        }

        now.set(START.plusSeconds(299).plusMillis(900));
        assertEquals(1L, assertThrows(RetryLater.class, () -> joinService.join(7L, ACTIVE_CODE)).retryAfterSeconds());
        assertSame(joined, joinService.join(8L, ACTIVE_CODE));
        now.set(START.plusSeconds(300));
        assertSame(joined, joinService.join(7L, ACTIVE_CODE));
        expectFailure(UNKNOWN_CODE, HttpStatus.NOT_FOUND);
        verify(sessions, times(2)).join(7L, ACTIVE_CODE);
    }

    @Test
    void unrelatedServiceErrorsDoNotConsumeTheInviteFailureBudget() {
        SessionResponse joined = mock(SessionResponse.class);
        when(sessions.join(7L, ACTIVE_CODE))
                .thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "나중에 다시 시도해 주세요"))
                .thenReturn(joined);

        expectFailure(ACTIVE_CODE, HttpStatus.SERVICE_UNAVAILABLE);
        for (int i = 0; i < 4; i++) {
            expectFailure(UNKNOWN_CODE, HttpStatus.NOT_FOUND);
        }
        assertSame(joined, joinService.join(7L, ACTIVE_CODE));
    }

    @Test
    void concurrentRequestsCannotExceedOneAccountsFailureBudget() throws Exception {
        int requests = 8;
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<HttpStatus>> responses = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                responses.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return assertThrows(ApiException.class, () -> joinService.join(7L, UNKNOWN_CODE)).getStatus();
                }));
            }
            boolean allReady = ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            assertTrue(allReady);
            List<HttpStatus> statuses = new ArrayList<>();
            for (Future<HttpStatus> response : responses) {
                statuses.add(response.get(5, TimeUnit.SECONDS));
            }
            assertEquals(5L, statuses.stream().filter(HttpStatus.NOT_FOUND::equals).count());
            assertEquals(3L, statuses.stream().filter(HttpStatus.TOO_MANY_REQUESTS::equals).count());
            verify(sessions, times(5)).join(7L, UNKNOWN_CODE);
        }
    }

    @Test
    void joinEndpointReturnsRetryAfterWithTheStandardErrorBody() throws Exception {
        for (int i = 0; i < 5; i++) {
            expectFailure(UNKNOWN_CODE, HttpStatus.NOT_FOUND);
        }
        var roles = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        var principal = new DefaultOAuth2User(roles, Map.of("id", "7"), "id");
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, roles));
        SecurityContextHolder.setContext(context);
        try {
            MockMvcBuilders.standaloneSetup(new StudySessionController(sessions, joinService))
                    .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                    .build()
                    .perform(post("/api/sessions/join").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"ROOM01\"}"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "300"))
                    .andExpect(jsonPath("$.status").value(429))
                    .andExpect(jsonPath("$.message").isNotEmpty());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void expectFailure(String code, HttpStatus status) {
        assertEquals(status, assertThrows(ApiException.class, () -> joinService.join(7L, code)).getStatus());
    }
}
