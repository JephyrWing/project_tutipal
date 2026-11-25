package com.my.tutipal_back.learning.review.controller;

import jakarta.servlet.Filter;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
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

/** Actual security/controller/service, mocked persistence. Real SQL checks use reviewDbTest. */
@SpringJUnitWebConfig(ReviewPrivacyWebTest.Config.class)
class ReviewPrivacyWebTest {
    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import({SecurityConfig.class, CsrfController.class, ReviewController.class, ReviewService.class, GlobalExceptionHandler.class})
    static class Config {
        @Bean ReviewStore store() { return mock(ReviewStore.class); }
        @Bean AiClient ai() { return mock(AiClient.class); }
        @Bean SocialOAuth2UserService oauth() { return mock(SocialOAuth2UserService.class); }
        @Bean AppLoginSuccessHandler success() { return mock(AppLoginSuccessHandler.class); }
    }
    @Autowired WebApplicationContext context;
    @Autowired ReviewStore store;
    MockMvc mvc;
    MockHttpSession session;

    @BeforeEach void setup() {
        reset(store);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
        var user = new User("dev", "review-test", "합성 사용자");
        ReflectionTestUtils.setField(user, "id", 101L);
        var principal = SocialOAuth2UserService.principalOf(user);
        var auth = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(auth));
    }

    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withCsrf(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, MockHttpSession login) throws Exception {
        var token = mvc.perform(get("/api/auth/csrf").session(login)).andReturn().getResponse().getCookie("XSRF-TOKEN");
        return request.session(login).cookie(token).header("X-XSRF-TOKEN", token.getValue());
    }

    @Test void anonymousCannotListOrRead() throws Exception {
        mvc.perform(get("/api/review/items")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/review/items/11")).andExpect(status().isUnauthorized());
        verifyNoInteractions(store);
    }

    @Test void anonymousCannotDeleteEvenWithCsrf() throws Exception {
        var anonymous = new MockHttpSession();
        mvc.perform(withCsrf(delete("/api/review/items/11"), anonymous)).andExpect(status().isUnauthorized());
        mvc.perform(withCsrf(delete("/api/review/items"), anonymous)).andExpect(status().isUnauthorized());
        verifyNoInteractions(store);
    }

    @Test void signedInDeleteRequiresCsrf() throws Exception {
        mvc.perform(delete("/api/review/items/11").session(session)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/review/items").session(session)).andExpect(status().isForbidden());
        verifyNoInteractions(store);
    }

    @Test void listUsesPrincipalAndIgnoresSpoofedOwner() throws Exception {
        when(store.list(101L)).thenReturn(List.of());
        mvc.perform(get("/api/review/items?userId=202").session(session)).andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(store).list(101L);
        verifyNoMoreInteractions(store);
    }

    @Test void foreignOrMissingDetailIs404() throws Exception {
        when(store.find(101L, 11L)).thenReturn(Optional.empty());
        mvc.perform(get("/api/review/items/11?userId=202").session(session)).andExpect(status().isNotFound());
        verify(store).find(101L, 11L);
        verifyNoMoreInteractions(store);
    }

    @Test void deleteOnlyUsesPrincipalIncludingForeignIds() throws Exception {
        // A non-owned ID affects zero rows; existing idempotent DELETE contract remains 204.
        when(store.delete(101L, 11L)).thenReturn(0);
        mvc.perform(withCsrf(delete("/api/review/items/11?userId=202"), session)).andExpect(status().isNoContent());
        verify(store).delete(101L, 11L);
        verifyNoMoreInteractions(store);
    }

    @Test void deleteAllOnlyUsesPrincipal() throws Exception {
        mvc.perform(withCsrf(delete("/api/review/items?userId=202"), session)).andExpect(status().isNoContent());
        verify(store).deleteAll(101L);
        verifyNoMoreInteractions(store);
    }
}
