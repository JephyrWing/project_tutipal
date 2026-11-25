package com.my.tutipal_back.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.my.tutipal_back.common.auth.controller.CsrfController;
import com.my.tutipal_back.common.auth.service.AppLoginSuccessHandler;
import com.my.tutipal_back.common.exception.GlobalExceptionHandler;
import com.my.tutipal_back.common.support.HealthController;
import com.my.tutipal_back.users.service.SocialOAuth2UserService;

/**
 * PT-100 리뷰 후속: HEAD 상태 확인, 기본 허용 Origin 패턴, Origin "null", 앱 로그인 시작 공개, dev-login 값 해석.
 * app.allowed-origins 를 지정하지 않아 SecurityConfig 기본값을 그대로 쓴다.
 */
@SpringJUnitWebConfig(SecurityPolicyFollowupWebTest.Config.class)
class SecurityPolicyFollowupWebTest {

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, CsrfController.class, HealthController.class, GlobalExceptionHandler.class, Probe.class})
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
        @PostMapping("/api/probe")
        String write() {
            return "ok";
        }

        @GetMapping("/auth/app/start")
        String appLoginStart() {
            return "ok";
        }

        @PostMapping("/dev/login")
        String devLogin() {
            return "ok";
        }
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = build(context);
    }

    // ---- A. HEAD 상태 확인 ----

    @Test
    void anonymousHeadHealthIsAllowed() throws Exception {
        mvc.perform(head("/health")).andExpect(status().is2xxSuccessful());
    }

    @Test
    void anonymousHeadCsrfIsUnauthorized() throws Exception {
        mvc.perform(head("/api/auth/csrf")).andExpect(status().isUnauthorized());
    }

    // ---- D. 기본 허용 Origin 패턴 ----

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:5173", "http://localhost", "http://127.0.0.1:8080", "http://127.0.0.1"})
    void defaultAllowedOriginsAcceptLocalhostWithOrWithoutPort(String origin) throws Exception {
        MockHttpSession session = loggedIn();
        Cookie token = csrfCookie(session);

        mvc.perform(post("/api/probe").session(session).cookie(token).header("X-XSRF-TOKEN", token.getValue())
                .header("Origin", origin)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:5173.evil.com", "http://localhost:@evil.com", "http://localhost.evil.com",
            "https://evil.example"})
    void defaultAllowedOriginsRejectLookalikeHosts(String origin) throws Exception {
        MockHttpSession session = loggedIn();
        Cookie token = csrfCookie(session);

        mvc.perform(post("/api/probe").session(session).cookie(token).header("X-XSRF-TOKEN", token.getValue())
                .header("Origin", origin)).andExpect(status().isForbidden());
    }

    @Test
    void applicationYamlDefaultMatchesSecurityConfigDefault() {
        String configured = CookieProfileConfigTest.environment(Map.of()).getProperty("app.allowed-origins");

        assertThat(configured).isEqualTo(SecurityConfig.DEFAULT_ALLOWED_ORIGINS);
    }

    // ---- E. Origin "null", 앱 로그인 시작 ----

    @Test
    void nullOriginOnStateChangingRequestIsForbidden() throws Exception {
        MockHttpSession session = loggedIn();
        Cookie token = csrfCookie(session);

        mvc.perform(post("/api/probe").session(session).cookie(token).header("X-XSRF-TOKEN", token.getValue())
                .header("Origin", "null")).andExpect(status().isForbidden());
    }

    @Test
    void anonymousGetAppLoginStartIsPublic() throws Exception {
        mvc.perform(get("/auth/app/start")).andExpect(status().isOk());
    }

    // ---- C. app.dev-login 은 "true" 만 켠 것으로 본다 ----

    @Nested
    @ActiveProfiles("local")
    @TestPropertySource(properties = "app.dev-login=on")
    class WhenDevLoginIsOnInsteadOfTrue {

        @Autowired
        private WebApplicationContext devContext;

        @Test
        void authConfigReportsDevLoginOff() throws Exception {
            build(devContext).perform(get("/api/auth/config"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.devLogin").value(false));
        }

        @Test
        void devLoginIsNotPublic() throws Exception {
            MockMvc devMvc = build(devContext);
            Cookie token = devMvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
            assertThat(token).isNotNull();

            devMvc.perform(post("/dev/login").cookie(token).header("X-XSRF-TOKEN", token.getValue()))
                    .andExpect(status().isUnauthorized());
        }
    }

    private static MockMvc build(WebApplicationContext ctx) {
        return MockMvcBuilders.webAppContextSetup(ctx)
                .addFilters(ctx.getBean("springSecurityFilterChain", Filter.class))
                .build();
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
