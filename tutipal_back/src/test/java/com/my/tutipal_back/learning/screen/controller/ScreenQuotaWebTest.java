package com.my.tutipal_back.learning.screen.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.jayway.jsonpath.JsonPath;
import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.ai.client.AiSlots;
import com.my.tutipal_back.ai.client.AiUnavailableException;
import com.my.tutipal_back.ai.quota.AiFeature;
import com.my.tutipal_back.ai.quota.AiQuota;
import com.my.tutipal_back.ai.quota.AiQuotaFixtures;
import com.my.tutipal_back.ai.quota.MutableClock;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.common.exception.GlobalExceptionHandler;
import com.my.tutipal_back.learning.screen.service.ScreenService;

/** PT-97 W1/W4: screen over quota → 429 problem+json; existing 503 {status,message} shape unchanged. */
class ScreenQuotaWebTest {

    private static final Instant T0 = Instant.parse("2026-11-25T00:00:00Z");
    private static final String PNG = Base64.getEncoder().encodeToString(
            new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13});

    private final AiClient ai = mock(AiClient.class);
    private final AiSlots slots = mock(AiSlots.class);
    private final AiQuota quota = AiQuotaFixtures.quota(1, Duration.ofHours(1), new MutableClock(T0));
    private MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        var principal = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")), Map.of("id", "2"), "id");
        var login = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(login);
        when(slots.run(any(Supplier.class))).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
        when(slots.run(any(Duration.class), any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        mvc = MockMvcBuilders.standaloneSetup(new ScreenController(new ScreenService(ai, slots, quota)))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearLogin() {
        SecurityContextHolder.clearContext();
    }

    // W1
    @Test
    void returns429ProblemDetailWhenScreenQuestionQuotaIsUsedUp() throws Exception {
        quota.reserve(2L, AiFeature.SCREEN_MANUAL);

        MvcResult result = mvc.perform(post("/api/screen").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"image\":\"" + PNG + "\",\"question\":\"이게 뭐야?\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Retry-After", "3600"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.feature").value("SCREEN_MANUAL"))
                .andExpect(jsonPath("$.retryAt").value("2026-11-25T01:00:00Z"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(3600))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String detail = JsonPath.read(body, "$.detail");
        String message = JsonPath.read(body, "$.message");
        assertThat(message).isEqualTo(detail);
        verifyNoInteractions(ai);
    }

    @Test
    void screenSummaryOverQuotaReportsScreenAuto() throws Exception {
        quota.reserve(2L, AiFeature.SCREEN_AUTO);

        mvc.perform(post("/api/screen").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"image\":\"" + PNG + "\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.feature").value("SCREEN_AUTO"));
    }

    @Test
    void reservesForTheLoggedInUserOnly() throws Exception {
        quota.reserve(99L, AiFeature.SCREEN_MANUAL);
        when(ai.complete(anyString(), anyList(), anyBoolean())).thenReturn("답");

        mvc.perform(post("/api/screen").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"image\":\"" + PNG + "\",\"question\":\"이게 뭐야?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("답"));

        assertThat(AiQuotaFixtures.hasRoom(quota, 2L, AiFeature.SCREEN_MANUAL)).isFalse();
    }

    // W4
    @Test
    @SuppressWarnings("unchecked")
    void slotTimeout503KeepsStatusMessageJsonShape() throws Exception {
        var busy = new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "지금 질문이 많아요. 잠시 후 다시 시도해 주세요");
        when(slots.run(any(Supplier.class))).thenThrow(busy);
        when(slots.run(any(Duration.class), any(Supplier.class))).thenThrow(busy);

        mvc.perform(post("/api/screen").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"image\":\"" + PNG + "\",\"question\":\"이게 뭐야?\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").value("지금 질문이 많아요. 잠시 후 다시 시도해 주세요"))
                .andExpect(jsonPath("$.feature").doesNotExist());
    }

    @Test
    void aiUnavailable503KeepsStatusMessageJsonShape() throws Exception {
        when(ai.complete(anyString(), anyList(), anyBoolean())).thenThrow(new AiUnavailableException());

        mvc.perform(post("/api/screen").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"image\":\"" + PNG + "\",\"question\":\"이게 뭐야?\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").value("AI 조교에 연결할 수 없어요"))
                .andExpect(jsonPath("$.feature").doesNotExist());
    }
}
