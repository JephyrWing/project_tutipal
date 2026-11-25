package com.my.tutipal_back.learning.review.controller;

import jakarta.servlet.Filter;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.common.auth.controller.CsrfController;
import com.my.tutipal_back.common.auth.service.AppLoginSuccessHandler;
import com.my.tutipal_back.common.config.SecurityConfig;
import com.my.tutipal_back.common.exception.GlobalExceptionHandler;
import com.my.tutipal_back.learning.review.repository.ReviewStore;
import com.my.tutipal_back.learning.review.service.ReviewService;
import com.my.tutipal_back.users.entity.User;
import com.my.tutipal_back.users.service.SocialOAuth2UserService;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Controller/service/security are real; DB access is covered separately by isolated Electron/PostgreSQL. */
@SpringJUnitWebConfig(ReviewHistoryWebTest.Config.class)
class ReviewHistoryWebTest {
    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import({SecurityConfig.class, CsrfController.class, ReviewController.class, ReviewService.class, GlobalExceptionHandler.class})
    static class Config {
        @Bean ReviewStore store() { return mock(ReviewStore.class); }
        @Bean AiClient ai() { return mock(AiClient.class); }
        @Bean SocialOAuth2UserService oauth() { return mock(SocialOAuth2UserService.class); }
        @Bean AppLoginSuccessHandler loginSuccess() { return mock(AppLoginSuccessHandler.class); }
    }
    @Autowired WebApplicationContext context;
    @Autowired ReviewStore store;
    MockMvc mvc;

    @BeforeEach void setup() {
        reset(store);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    MockHttpSession login() {
        User user = new User("google", "review-owner", "본인");
        ReflectionTestUtils.setField(user, "id", 7L);
        var principal = SocialOAuth2UserService.principalOf(user);
        var auth = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(auth));
        return session;
    }

    @Test void anonymousCannotReadHistoryOrDetail() throws Exception {
        mvc.perform(get("/api/review/history")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/review/items/11")).andExpect(status().isUnauthorized());
        verifyNoInteractions(store);
    }

    @Test void ignoresSpoofedUserIdAndReturnsSafePage() throws Exception {
        var now = Instant.parse("2026-11-23T00:00:00Z");
        when(store.count(7L)).thenReturn(105L);
        when(store.history(7L, 20, 0L)).thenReturn(List.of(new ReviewStore.Item(11L, "반복문", 3, 120, now, now, null)));
        mvc.perform(get("/api/review/history").session(login()).param("userId", "99"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].summary").value("반복문"))
                .andExpect(jsonPath("$.totalCount").value(105)).andExpect(jsonPath("$.totalPages").value(6))
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items[0].embedding").doesNotExist())
                .andExpect(jsonPath("$.items[0].userId").doesNotExist());
        verify(store).count(7L);
        verify(store).history(7L, 20, 0L);
        verifyNoMoreInteractions(store);
    }

    @Test void rejectsInvalidQueryIncludingNonInteger() throws Exception {
        for (String query : List.of("page=-1", "size=0", "size=101", "page=abc", "page=2147483648")) {
            mvc.perform(get("/api/review/history?" + query).session(login())).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(store);
    }

    @Test void missingAndOtherUsersDetailAreBoth404() throws Exception {
        when(store.find(7L, 99L)).thenReturn(Optional.empty());
        mvc.perform(get("/api/review/items/99").session(login())).andExpect(status().isNotFound());
        verify(store).find(7L, 99L);
        verify(store, never()).get(anyLong());
    }

    @Test void legacyListStillReturnsArray() throws Exception {
        when(store.list(7L)).thenReturn(List.of());
        mvc.perform(get("/api/review/items").session(login())).andExpect(status().isOk()).andExpect(content().json("[]"));
    }
}
