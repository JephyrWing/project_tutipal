package com.my.tutipal_back.learning.tutor.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.ai.client.AiSlots;
import com.my.tutipal_back.ai.quota.AiFeature;
import com.my.tutipal_back.ai.quota.AiQuota;
import com.my.tutipal_back.ai.quota.AiQuotaFixtures;
import com.my.tutipal_back.ai.quota.MutableClock;
import com.my.tutipal_back.common.exception.GlobalExceptionHandler;
import com.my.tutipal_back.learning.tutor.service.TutorService;
import com.my.tutipal_back.rag.service.RagSearchService;
import com.my.tutipal_back.sessions.service.StudySessionService;
import com.my.tutipal_back.learning.tutor.service.StudentQuestionSink;

/** PT-97 W2: chat over quota is an HTTP 429 problem+json before any SSE stream opens. */
class TutorChatQuotaWebTest {

    private final StudySessionService sessions = mock(StudySessionService.class);
    private final RagSearchService rag = mock(RagSearchService.class);
    private final AiClient ai = mock(AiClient.class);
    private final AiSlots slots = mock(AiSlots.class);
    private final AiQuota quota = AiQuotaFixtures.quota(1, Duration.ofHours(1),
            new MutableClock(Instant.parse("2026-11-25T00:00:00Z")));
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var principal = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")), Map.of("id", "2"), "id");
        var login = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(login);
        mvc = MockMvcBuilders.standaloneSetup(new TutorController(new TutorService(ai, sessions, rag, slots, quota, null, null, false,
                StudentQuestionSink.NONE)))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearLogin() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"topic\":\"수학\",\"messages\":[{\"role\":\"user\",\"content\":\"설명해 주세요\"}]}",
        "{\"topic\":\"수학\",\"sessionId\":10,\"messages\":[{\"role\":\"user\",\"content\":\"설명해 주세요\"}]}"
    })
    void returns429ProblemDetailInsteadOfOpeningStream(String body) throws Exception {
        quota.reserve(2L, AiFeature.CHAT);

        mvc.perform(post("/api/tutor/chat").accept(MediaType.TEXT_EVENT_STREAM).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Retry-After", "3600"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.feature").value("CHAT"))
                .andExpect(jsonPath("$.retryAt").value("2026-11-25T01:00:00Z"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(3600))
                .andExpect(request().asyncNotStarted());

        verifyNoInteractions(rag, ai, slots);
    }
}
