package com.my.tutipal_back.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpSession;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.my.tutipal_back.common.auth.controller.CsrfController;
import com.my.tutipal_back.common.auth.service.AppLoginSuccessHandler;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.common.exception.GlobalExceptionHandler;
import com.my.tutipal_back.common.support.HealthController;
import com.my.tutipal_back.users.service.SocialOAuth2UserService;

/**
 * PT-25 NFR-SEC-01 / PT-100: 실제 SecurityConfig 필터 체인만 올려서 로그인·CSRF·Origin·쿠키·로그아웃 규칙을 확인한다 (DB 없음).
 * 업무 컨트롤러 대신 같은 경로 규칙을 타는 시험용 컨트롤러(Probe)를 쓴다.
 */
@SpringJUnitWebConfig(SecurityConfigWebTest.Config.class)
@TestPropertySource(properties = {
        "app.allowed-origins=http://127.0.0.1:*,http://localhost:*,https://app.tutipal.test",
        "server.servlet.session.cookie.secure=true"
})
class SecurityConfigWebTest {

    static final String ALLOWED_ORIGIN = "https://app.tutipal.test";
    static final String ALLOWED_LOCAL_ORIGIN = "http://localhost:5173";
    static final String FOREIGN_ORIGIN = "https://evil.example";

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
        @GetMapping("/api/probe")
        String read() {
            return "ok";
        }

        @PostMapping("/api/probe")
        String write() {
            return "ok";
        }

        @PutMapping("/api/probe")
        String replace() {
            return "ok";
        }

        @PatchMapping("/api/probe")
        String change() {
            return "ok";
        }

        @DeleteMapping("/api/probe")
        String remove() {
            return "ok";
        }

        @GetMapping("/api/probe/other-class")
        String otherClass() {
            throw new ApiException(HttpStatus.NOT_FOUND, "수업을 찾을 수 없어요");
        }

        @GetMapping("/api/probe/teacher-only")
        String teacherOnly() {
            throw new ApiException(HttpStatus.FORBIDDEN, "선생님만 할 수 있어요");
        }

        @GetMapping("/api/probe/ended")
        String ended() {
            throw new ApiException(HttpStatus.GONE, "끝난 수업이에요");
        }

        @GetMapping("/api/admin/probe")
        String admin() {
            return "ok";
        }

        @PostMapping("/api/auth/app/exchange")
        String exchange() {
            return "ok";
        }

        @PostMapping("/dev/login")
        String devLogin() {
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
        mvc = build(context);
    }

    // ---- 1. 공개 GET ----

    @Test
    void publicGetEndpointsAreReachableWithoutLogin() throws Exception {
        mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk());
        mvc.perform(get("/api/auth/providers")).andExpect(status().isOk());
        mvc.perform(get("/api/auth/config")).andExpect(status().isOk());
        mvc.perform(get("/health")).andExpect(status().isOk());
    }

    // ---- 2. 공개 경로는 GET 만 공개 ----

    @Test
    void anonymousPostToCsrfEndpointIsUnauthorized() throws Exception {
        Cookie token = anonymousCsrfCookie();

        mvc.perform(post("/api/auth/csrf").cookie(token).header("X-XSRF-TOKEN", token.getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousPostToProvidersAndConfigIsUnauthorized() throws Exception {
        Cookie token = anonymousCsrfCookie();

        mvc.perform(post("/api/auth/providers").cookie(token).header("X-XSRF-TOKEN", token.getValue()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/config").cookie(token).header("X-XSRF-TOKEN", token.getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousNonGetToAppLoginPathsIsUnauthorized() throws Exception {
        Cookie token = anonymousCsrfCookie();

        mvc.perform(post("/auth/app/start").cookie(token).header("X-XSRF-TOKEN", token.getValue()))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/auth/app/done").cookie(token).header("X-XSRF-TOKEN", token.getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousNonGetToOAuth2PathsIsUnauthorized() throws Exception {
        Cookie token = anonymousCsrfCookie();

        mvc.perform(post("/oauth2/authorization/google").cookie(token).header("X-XSRF-TOKEN", token.getValue()))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/oauth2/authorization/google").cookie(token).header("X-XSRF-TOKEN", token.getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void healthIsPublicOnlyForGet() throws Exception {
        Cookie token = anonymousCsrfCookie();

        mvc.perform(post("/health").cookie(token).header("X-XSRF-TOKEN", token.getValue()))
                .andExpect(status().isUnauthorized());
    }

    // ---- 3, 4. 비로그인 401 / 허용 목록 밖 403 ----

    @Test
    void apiWithoutLoginIsUnauthorizedNotRedirected() throws Exception {
        mvc.perform(get("/api/probe")).andExpect(status().isUnauthorized());
    }

    @Test
    void pathsOutsideTheAllowListAreDeniedEvenWhenLoggedIn() throws Exception {
        mvc.perform(get("/internal/probe").session(loggedIn())).andExpect(status().isForbidden());
    }

    // ---- 5. 업무 예외 → GlobalExceptionHandler 응답 ----

    @Test
    void otherClassResourceIsNotFoundThroughExceptionHandler() throws Exception {
        mvc.perform(get("/api/probe/other-class").session(loggedIn()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void studentOnTeacherResourceIsForbiddenThroughExceptionHandler() throws Exception {
        mvc.perform(get("/api/probe/teacher-only").session(loggedIn()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void endedSessionIsGoneThroughExceptionHandler() throws Exception {
        mvc.perform(get("/api/probe/ended").session(loggedIn()))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.status").value(410));
    }

    // ---- 6. CSRF ----

    @Test
    void csrfEndpointIsPublicAndIssuesReadableCookie() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();

        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isFalse();   // 화면 JS 가 읽어 X-XSRF-TOKEN 헤더로 보낸다
        assertThat(result.getResponse().getContentAsString()).contains("X-XSRF-TOKEN");
        // 9. SameSite=Lax, Secure = server.servlet.session.cookie.secure (여기서는 true)
        assertThat(cookie.getAttribute("SameSite")).isEqualToIgnoringCase("Lax");
        assertThat(cookie.getSecure()).isTrue();
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
    void loggedInPutPatchDeleteWithoutCsrfTokenAreForbiddenAndSessionSurvives() throws Exception {
        MockHttpSession session = loggedIn();

        mvc.perform(put("/api/probe").session(session)).andExpect(status().isForbidden());
        mvc.perform(patch("/api/probe").session(session)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/probe").session(session)).andExpect(status().isForbidden());

        assertThat(session.isInvalid()).isFalse();
        mvc.perform(get("/api/probe").session(session)).andExpect(status().isOk());
    }

    @Test
    void loggedInPutPatchDeleteWithWrongCsrfTokenAreForbiddenAndSessionSurvives() throws Exception {
        MockHttpSession session = loggedIn();
        Cookie token = csrfCookie(session);

        mvc.perform(put("/api/probe").session(session).cookie(token).header("X-XSRF-TOKEN", "not-the-token"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/probe").session(session).cookie(token).header("X-XSRF-TOKEN", "not-the-token"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/probe").session(session).cookie(token).header("X-XSRF-TOKEN", "not-the-token"))
                .andExpect(status().isForbidden());

        assertThat(session.isInvalid()).isFalse();
        mvc.perform(get("/api/probe").session(session)).andExpect(status().isOk());
    }

    @Test
    void loggedInPutPatchDeleteWithMatchingCsrfTokenAreAllowed() throws Exception {
        MockHttpSession session = loggedIn();
        Cookie token = csrfCookie(session);

        mvc.perform(withCsrf(put("/api/probe"), session, token)).andExpect(status().isOk());
        mvc.perform(withCsrf(patch("/api/probe"), session, token)).andExpect(status().isOk());
        mvc.perform(withCsrf(delete("/api/probe"), session, token)).andExpect(status().isOk());
    }

    // ---- 7. CSRF 예외는 앱 토큰 교환 하나 ----

    @Test
    void appTokenExchangeIsTheOnlyCsrfExemptPost() throws Exception {
        mvc.perform(post("/api/auth/app/exchange")).andExpect(status().isOk());
    }

    // ---- 8. Origin 검사 ----

    @Test
    void postWithForeignOriginIsForbiddenEvenWithValidCsrf() throws Exception {
        MockHttpSession session = loggedIn();
        Cookie token = csrfCookie(session);

        mvc.perform(withCsrf(post("/api/probe"), session, token).header("Origin", FOREIGN_ORIGIN))
                .andExpect(status().isForbidden());
    }

    @Test
    void putPatchDeleteWithForeignOriginAreForbiddenEvenWithValidCsrf() throws Exception {
        MockHttpSession session = loggedIn();
        Cookie token = csrfCookie(session);

        mvc.perform(withCsrf(put("/api/probe"), session, token).header("Origin", FOREIGN_ORIGIN))
                .andExpect(status().isForbidden());
        mvc.perform(withCsrf(patch("/api/probe"), session, token).header("Origin", FOREIGN_ORIGIN))
                .andExpect(status().isForbidden());
        mvc.perform(withCsrf(delete("/api/probe"), session, token).header("Origin", FOREIGN_ORIGIN))
                .andExpect(status().isForbidden());
    }

    @Test
    void stateChangingRequestWithoutOriginIsNotBlockedByOriginCheck() throws Exception {
        MockHttpSession session = loggedIn();
        Cookie token = csrfCookie(session);

        mvc.perform(withCsrf(put("/api/probe"), session, token)).andExpect(status().isOk());
    }

    @Test
    void requestWithoutOriginStillNeedsCsrf() throws Exception {
        mvc.perform(post("/api/probe").session(loggedIn())).andExpect(status().isForbidden());
    }

    @Test
    void postWithAllowedOriginIsAllowed() throws Exception {
        MockHttpSession session = loggedIn();
        Cookie token = csrfCookie(session);

        mvc.perform(withCsrf(post("/api/probe"), session, token).header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isOk());
        mvc.perform(withCsrf(post("/api/probe"), session, token).header("Origin", ALLOWED_LOCAL_ORIGIN))
                .andExpect(status().isOk());
    }

    @Test
    void getWithForeignOriginIsNotCheckedForOrigin() throws Exception {
        mvc.perform(get("/api/probe").session(loggedIn()).header("Origin", FOREIGN_ORIGIN))
                .andExpect(status().isOk());
    }

    @Test
    void appTokenExchangeWithForeignOriginIsForbidden() throws Exception {
        mvc.perform(post("/api/auth/app/exchange").header("Origin", FOREIGN_ORIGIN))
                .andExpect(status().isForbidden());
    }

    @Test
    void appTokenExchangeWithAllowedOriginIsAllowed() throws Exception {
        mvc.perform(post("/api/auth/app/exchange").header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isOk());
    }

    // ---- 12. 개발 로그인 꺼짐 ----

    @Test
    void devLoginIsUnauthorizedWhenDevLoginIsOff() throws Exception {
        Cookie token = anonymousCsrfCookie();

        MvcResult result = mvc.perform(post("/dev/login").cookie(token).header("X-XSRF-TOKEN", token.getValue()))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertNoAuthenticationInSession(result.getRequest().getSession(false));
    }

    @Test
    void authConfigReportsDevLoginOffWhenDisabled() throws Exception {
        mvc.perform(get("/api/auth/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.devLogin").value(false));
    }

    // ---- 17. 관리자 경로 ----

    @Test
    void adminPathIsForbiddenForRegularUser() throws Exception {
        mvc.perform(get("/api/admin/probe").session(loggedIn())).andExpect(status().isForbidden());
    }

    @Test
    void adminPathIsAllowedForAdmin() throws Exception {
        mvc.perform(get("/api/admin/probe").session(loggedIn("ROLE_ADMIN"))).andExpect(status().isOk());
    }

    @Test
    void adminPathWithoutLoginIsUnauthorized() throws Exception {
        mvc.perform(get("/api/admin/probe")).andExpect(status().isUnauthorized());
    }

    // ---- 로그아웃 ----

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

    /** 9. 세션 쿠키 Secure 를 끈 환경(로컬 http)에서는 XSRF-TOKEN 도 Secure 가 아니고 SameSite=Lax 는 유지한다. */
    @Nested
    @TestPropertySource(properties = "server.servlet.session.cookie.secure=false")
    class WhenSessionCookieIsNotSecure {

        @Autowired
        private WebApplicationContext insecureContext;

        @Test
        void csrfCookieFollowsSessionCookieSecureFlag() throws Exception {
            MvcResult result = build(insecureContext).perform(get("/api/auth/csrf"))
                    .andExpect(status().isOk()).andReturn();

            Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
            assertThat(cookie).isNotNull();
            assertThat(cookie.getSecure()).isFalse();
            assertThat(cookie.getAttribute("SameSite")).isEqualToIgnoringCase("Lax");
        }
    }

    /** 12. local 프로필 + app.dev-login=true 일 때만 /dev/login 이 공개되고 /api/auth/config 도 같은 판단을 보여준다. */
    @Nested
    @ActiveProfiles("local")
    @TestPropertySource(properties = "app.dev-login=true")
    class WhenDevLoginIsEnabledLocally {

        @Autowired
        private WebApplicationContext devContext;

        @Test
        void devLoginIsReachableWithCsrf() throws Exception {
            MockMvc devMvc = build(devContext);
            Cookie token = devMvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
            assertThat(token).isNotNull();

            devMvc.perform(post("/dev/login").cookie(token).header("X-XSRF-TOKEN", token.getValue()))
                    .andExpect(status().isOk());
        }

        @Test
        void devLoginStillNeedsCsrf() throws Exception {
            build(devContext).perform(post("/dev/login")).andExpect(status().isForbidden());
        }

        @Test
        void authConfigReportsDevLoginOn() throws Exception {
            build(devContext).perform(get("/api/auth/config"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.devLogin").value(true));
        }
    }

    private static MockMvc build(WebApplicationContext ctx) {
        return MockMvcBuilders.webAppContextSetup(ctx)
                .addFilters(ctx.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request, MockHttpSession session, Cookie token) {
        return request.session(session).cookie(token).header("X-XSRF-TOKEN", token.getValue());
    }

    private static void assertNoAuthenticationInSession(HttpSession session) {
        if (session == null) {
            return;
        }
        Object context = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (context instanceof SecurityContext securityContext) {
            assertThat(securityContext.getAuthentication()).isNull();
        } else {
            assertThat(context).isNull();
        }
    }

    private static MockHttpSession loggedIn() {
        return loggedIn("ROLE_USER");
    }

    private static MockHttpSession loggedIn(String role) {
        MockHttpSession session = new MockHttpSession();
        var auth = UsernamePasswordAuthenticationToken.authenticated("1", null, List.of(new SimpleGrantedAuthority(role)));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(auth));
        return session;
    }

    private Cookie anonymousCsrfCookie() throws Exception {
        Cookie cookie = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private Cookie csrfCookie(MockHttpSession session) throws Exception {
        Cookie cookie = mvc.perform(get("/api/auth/csrf").session(session)).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return cookie;
    }
}
