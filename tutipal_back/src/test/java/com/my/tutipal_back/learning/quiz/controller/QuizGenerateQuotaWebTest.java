package com.my.tutipal_back.learning.quiz.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.my.tutipal_back.ai.quota.AiFeature;
import com.my.tutipal_back.ai.quota.AiQuota;
import com.my.tutipal_back.ai.quota.AiQuotaFixtures;
import com.my.tutipal_back.ai.quota.MutableClock;
import com.my.tutipal_back.common.exception.GlobalExceptionHandler;
import com.my.tutipal_back.common.realtime.SessionEvents;
import com.my.tutipal_back.learning.quiz.repository.QuizAnswerRepository;
import com.my.tutipal_back.learning.quiz.repository.QuizRepository;
import com.my.tutipal_back.learning.quiz.service.QuizAi;
import com.my.tutipal_back.learning.quiz.service.QuizService;
import com.my.tutipal_back.sessions.dto.ChatSettings;
import com.my.tutipal_back.sessions.service.StudySessionService;
import com.my.tutipal_back.users.service.UserService;

/** PT-97 W3: AI quiz generation over quota → 429 problem+json with feature QUIZ_GENERATE. */
class QuizGenerateQuotaWebTest {

    private final StudySessionService sessions = mock(StudySessionService.class);
    private final QuizAi quizAi = mock(QuizAi.class);
    private final AiQuota quota = AiQuotaFixtures.quota(1, Duration.ofHours(1),
            new MutableClock(Instant.parse("2026-11-25T00:00:00Z")));
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var principal = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")), Map.of("id", "2"), "id");
        var login = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(login);
        when(sessions.chatSettings(2L, 10L)).thenReturn(new ChatSettings("수학", 3, false, false));
        var service = new QuizService(mock(QuizRepository.class), mock(QuizAnswerRepository.class), sessions,
                mock(UserService.class), quizAi, mock(SessionEvents.class), quota);
        mvc = MockMvcBuilders.standaloneSetup(new QuizController(service))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearLogin() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returns429ProblemDetailWhenQuizGenerationQuotaIsUsedUp() throws Exception {
        quota.reserve(2L, AiFeature.QUIZ_GENERATE);

        mvc.perform(post("/api/sessions/10/quizzes/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CHOICE\",\"hint\":\"미분\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Retry-After", "3600"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.feature").value("QUIZ_GENERATE"))
                .andExpect(jsonPath("$.retryAt").value("2026-11-25T01:00:00Z"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(3600));

        verifyNoInteractions(quizAi);
    }
}
