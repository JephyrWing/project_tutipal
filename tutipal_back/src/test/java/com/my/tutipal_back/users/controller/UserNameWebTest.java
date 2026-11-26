package com.my.tutipal_back.users.controller;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
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

import com.my.tutipal_back.common.auth.controller.CsrfController;
import com.my.tutipal_back.common.auth.service.AppLoginSuccessHandler;
import com.my.tutipal_back.common.config.SecurityConfig;
import com.my.tutipal_back.common.exception.GlobalExceptionHandler;
import com.my.tutipal_back.users.entity.User;
import com.my.tutipal_back.users.repository.AccountLocks;
import com.my.tutipal_back.users.repository.UserRepository;
import com.my.tutipal_back.users.service.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual controller/service/security filters; only persistence and external OAuth are mocked. */
@SpringJUnitWebConfig(UserNameWebTest.Config.class)
class UserNameWebTest {
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, CsrfController.class, UserController.class, UserService.class, GlobalExceptionHandler.class})
    static class Config {
        @Bean UserRepository users() { return mock(UserRepository.class); }
        @Bean AccountLocks accountLocks() { return mock(AccountLocks.class); }
        @Bean WithdrawalService withdrawal() { return mock(WithdrawalService.class); }
        @Bean SocialOAuth2UserService oauth() { return mock(SocialOAuth2UserService.class); }
        @Bean AppLoginSuccessHandler loginSuccess() { return mock(AppLoginSuccessHandler.class); }
    }

    @Autowired WebApplicationContext context;
    @Autowired UserRepository repository;
    MockMvc mvc;
    User owner;

    @BeforeEach
    void setup() {
        reset(repository);
        owner = new User("google", "owner", "기존 이름");
        ReflectionTestUtils.setField(owner, "id", 12L);
        when(repository.findById(12L)).thenReturn(Optional.of(owner));
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    private MockHttpSession login() {
        var principal = SocialOAuth2UserService.principalOf(owner);
        var auth = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(auth));
        return session;
    }

    private Cookie csrf(MockHttpSession session) throws Exception {
        return mvc.perform(get("/api/auth/csrf").session(session)).andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    @Test
    void unauthenticatedCannotUpdateEvenWithCsrf() throws Exception {
        var session = new MockHttpSession();
        var token = csrf(session);
        mvc.perform(patch("/api/users/me/name").session(session).cookie(token)
                .header("X-XSRF-TOKEN", token.getValue()).contentType("application/json").content("{\"name\":\"new\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(repository);
    }

    @Test
    void authenticatedWithoutCsrfIsForbidden() throws Exception {
        mvc.perform(patch("/api/users/me/name").session(login()).contentType("application/json").content("{\"name\":\"new\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(repository);
    }

    @Test
    void updatesOnlyPrincipalAndReturnsSafeProfile() throws Exception {
        var session = login();
        var token = csrf(session);
        mvc.perform(patch("/api/users/me/name").session(session).cookie(token)
                .header("X-XSRF-TOKEN", token.getValue()).contentType("application/json")
                .content("{\"name\":\" 새 이름 \",\"userId\":99,\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("새 이름"))
                .andExpect(jsonPath("$.id").value(12)).andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.provider").value("google"))
                .andExpect(jsonPath("$.email").doesNotExist()).andExpect(jsonPath("$.providerId").doesNotExist());
        verify(repository, never()).findById(99L);
        mvc.perform(get("/api/users/me").session(session)).andExpect(jsonPath("$.name").value("새 이름"));
    }

    @Test
    void blankAndMissingNamesReturn400WithoutChangingName() throws Exception {
        var session = login();
        var token = csrf(session);
        for (String body : List.of("{}", "{\"name\":\"   \"}")) {
            mvc.perform(patch("/api/users/me/name").session(session).cookie(token)
                    .header("X-XSRF-TOKEN", token.getValue()).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(owner.getName()).isEqualTo("기존 이름");
    }
}
