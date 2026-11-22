package com.my.tutipal_back.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.my.tutipal_back.common.auth.controller.CsrfController;
import com.my.tutipal_back.common.auth.service.AppLoginSuccessHandler;
import com.my.tutipal_back.users.service.SocialOAuth2UserService;

/**
 * PT-25 NFR-SEC-01: 실제 SecurityConfig 필터 체인만 올려서 로그인·CSRF·로그아웃 규칙을 확인한다 (DB 없음).
 * 업무 컨트롤러 대신 같은 경로 규칙을 타는 시험용 컨트롤러(Probe)를 쓴다.
 */
@SpringJUnitWebConfig(SecurityConfigWebTest.Config.class)
class SecurityConfigWebTest {

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, CsrfController.class, Probe.class})
    static class Config {
        @Bean
        SocialOAuth2UserService socialOAuth2UserService() {
            return mock(SocialOAuth2UserService.class);
        }

        @Bean
        AppLoginSuccessHandler appLoginSuccessHandler() {
            return mock(AppLoginSuccessHandler.class);
        }
    }

    @RestController
    static class Probe {
        @GetMapping("/api/probe")
        String read() {
            return "ok";
        }

        @PostMapping("/api/probe")
        String write() {
            return "ok";
        }

        @PostMapping("/api/auth/app/exchange")
        String exchange() {
            return "ok";
        }

        @GetMapping("/internal/probe")
        String unlisted() {
            return "ok";
        }
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    @Test
    void apiWithoutLoginIsUnauthorizedNotRedirected() throws Exception {
        mvc.perform(get("/api/probe")).andExpect(status().isUnauthorized());
    }

    @Test
    void csrfEndpointIsPublicAndIssuesReadableCookie() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();

        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isFalse();   // 화면 JS 가 읽어 X-XSRF-TOKEN 헤더로 보낸다
        assertThat(result.getResponse().getContentAsString()).contains("X-XSRF-TOKEN");
    }

    @Test
    void loggedInGetNeedsNoCsrfToken() throws Exception {
        mvc.perform(get("/api/probe").session(loggedIn())).andExpect(status().isOk());
    }

    @Test
    void loggedInPostWithoutCsrfTokenIsForbidden() throws Exception {
        mvc.perform(post("/api/probe").session(loggedIn())).andExpect(status().isForbidden());
    }

    @Test
    void loggedInPostWithWrongCsrfTokenIsForbidden() throws Exception {
        MockHttpSession session = loggedIn();
        Cookie token = csrfCookie(session);

        mvc.perform(post("/api/probe").session(session).cookie(token).header("X-XSRF-TOKEN", "not-the-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void loggedInPostWithMatchingCsrfTokenIsAllowed() throws Exception {
        MockHttpSession session = loggedIn();
        Cookie token = csrfCookie(session);

        mvc.perform(post("/api/probe").session(session).cookie(token).header("X-XSRF-TOKEN", token.getValue()))
                .andExpect(status().isOk());
    }

    @Test
    void appTokenExchangeIsTheOnlyCsrfExemptPost() throws Exception {
        mvc.perform(post("/api/auth/app/exchange")).andExpect(status().isOk());
    }

    @Test
    void pathsOutsideTheAllowListAreDeniedEvenWhenLoggedIn() throws Exception {
        mvc.perform(get("/internal/probe").session(loggedIn())).andExpect(status().isForbidden());
    }

    @Test
    void logoutReturnsNoContentAndEndsTheSession() throws Exception {
        MockHttpSession session = loggedIn();
        Cookie token = csrfCookie(session);

        mvc.perform(post("/logout").session(session).cookie(token).header("X-XSRF-TOKEN", token.getValue()))
                .andExpect(status().isNoContent());

        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/probe").cookie(token)).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutWithoutCsrfTokenIsRejectedAndSessionSurvives() throws Exception {
        MockHttpSession session = loggedIn();

        mvc.perform(post("/logout").session(session)).andExpect(status().isForbidden());

        assertThat(session.isInvalid()).isFalse();
        mvc.perform(get("/api/probe").session(session)).andExpect(status().isOk());
    }

    private static MockHttpSession loggedIn() {
        MockHttpSession session = new MockHttpSession();
        var auth = UsernamePasswordAuthenticationToken.authenticated("1", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(auth));
        return session;
    }

    private Cookie csrfCookie(MockHttpSession session) throws Exception {
        Cookie cookie = mvc.perform(get("/api/auth/csrf").session(session)).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return cookie;
    }
}
