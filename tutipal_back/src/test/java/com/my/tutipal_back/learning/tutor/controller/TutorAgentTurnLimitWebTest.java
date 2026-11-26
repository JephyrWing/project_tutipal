package com.my.tutipal_back.learning.tutor.controller;

import static com.my.tutipal_back.ai.quota.AiQuotaFixtures.hasRoom;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
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

import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.ai.client.AiSlots;
import com.my.tutipal_back.ai.quota.AiFeature;
import com.my.tutipal_back.ai.quota.AiQuota;
import com.my.tutipal_back.ai.quota.AiQuotaFixtures;
import com.my.tutipal_back.ai.quota.MutableClock;
import com.my.tutipal_back.common.exception.GlobalExceptionHandler;
import com.my.tutipal_back.learning.quiz.service.QuizVariantAccess;
import com.my.tutipal_back.learning.tutor.dto.ChatRequest;
import com.my.tutipal_back.learning.tutor.service.AgentContextAssembler;
import com.my.tutipal_back.learning.tutor.service.NoStoredConversationMemory;
import com.my.tutipal_back.learning.tutor.service.OpenedChat;
import com.my.tutipal_back.learning.tutor.service.TutorService;
import com.my.tutipal_back.rag.service.RagSearchService;
import com.my.tutipal_back.sessions.service.StudySessionService;
import com.my.tutipal_back.learning.tutor.service.StudentQuestionSink;

/** PT-85 Round 1 review S7 over HTTP: a second agent turn of the same user is a plain 429, not an SSE stream. */
class TutorAgentTurnLimitWebTest {

    private static final long USER = 2L;
    private static final String PERSONAL_JSON = """
            {"topic":"수학","messages":[{"role":"user","content":"미분이 뭐야?"}]}
            """;

    private final AiClient ai = mock(AiClient.class);
    private final AiSlots slots = mock(AiSlots.class);
    private final AiQuota quota = AiQuotaFixtures.quota(2, Duration.ofHours(1),
            new MutableClock(Instant.parse("2026-11-25T00:00:00Z")));
    private TutorService tutor;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var principal = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("id", String.valueOf(USER)), "id");
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        tutor = new TutorService(ai, mock(StudySessionService.class), mock(RagSearchService.class), slots, quota,
                mock(QuizVariantAccess.class), new AgentContextAssembler(new NoStoredConversationMemory()), true,
                StudentQuestionSink.NONE);
        mvc = MockMvcBuilders.standaloneSetup(new TutorController(tutor))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearLogin() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void secondTurnWhileTheFirstRunsIs429JsonBeforeTheStream() throws Exception {
        OpenedChat running = tutor.openChat(USER, new ChatRequest("수학",
                List.of(new ChatRequest.Message("user", "첫 질문")), null, null, null));

        var result = mvc.perform(post("/api/tutor/chat").accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON).content(PERSONAL_JSON))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andReturn();

        assertThat(result.getRequest().isAsyncStarted()).isFalse();
        verifyNoInteractions(ai, slots);
        assertThat(hasRoom(quota, USER, AiFeature.CHAT)).isTrue();

        running.release();
        mvc.perform(post("/api/tutor/chat").accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON).content(PERSONAL_JSON))
                .andExpect(request().asyncStarted());
    }
}
