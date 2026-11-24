package com.my.tutipal_back.learning.tutor.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
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
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.common.exception.GlobalExceptionHandler;
import com.my.tutipal_back.learning.tutor.service.TutorService;
import com.my.tutipal_back.rag.service.RagSearchService;
import com.my.tutipal_back.sessions.service.StudySessionService;

class TutorChatAccessWebTest {

    private final StudySessionService sessions = mock(StudySessionService.class);
    private final RagSearchService rag = mock(RagSearchService.class);
    private final AiClient ai = mock(AiClient.class);
    private final AiSlots slots = mock(AiSlots.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var principal = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")), Map.of("id", "2"), "id");
        var login = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(login);
        mvc = MockMvcBuilders.standaloneSetup(new TutorController(new TutorService(ai, sessions, rag, slots)))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearLogin() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"NOT_FOUND", "GONE"})
    void returnsHttpErrorBeforeOpeningAnUnauthorizedChatStream(HttpStatus denial) throws Exception {
        when(sessions.requireActiveRole(2L, 10L)).thenThrow(new ApiException(denial, "세션에 접근할 수 없어요"));

        mvc.perform(post("/api/tutor/chat").accept(MediaType.TEXT_EVENT_STREAM).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"topic":"수학","sessionId":10,"messages":[{"role":"user","content":"설명해 주세요"}]}
                        """))
                .andExpect(status().is(denial.value()))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(denial.value()))
                .andExpect(request().asyncNotStarted());

        verifyNoInteractions(rag, ai, slots);
    }
}
