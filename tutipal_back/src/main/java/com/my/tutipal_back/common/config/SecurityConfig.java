package com.my.tutipal_back.common.config;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import com.my.tutipal_back.common.auth.service.AppLoginSuccessHandler;
import com.my.tutipal_back.users.service.SocialOAuth2UserService;

/**
 * HTTP 접근 정책.
 * - 공개 경로는 GET 만 공개한다. 비로그인 POST 공개는 앱 토큰 교환(POST /api/auth/app/exchange) 하나.
 * - /api/admin/** 는 ADMIN, 나머지 /api/**·/ws·/livekit/** 는 로그인 필요(비로그인 401), 목록 밖은 모두 거부(로그인 403).
 * - CSRF 는 상태를 바꾸는 모든 요청에 적용하고 예외는 앱 토큰 교환 하나. Origin 검사는 OriginCheckFilter.
 * - /dev/login 은 DevLogin.enabled 일 때만 공개한다.
 * 설정은 Environment 에서 직접 읽는다 (Boot 없이 필터 체인만 올리는 웹 시험에서도 같은 기본값을 쓰도록).
 */
@Configuration
public class SecurityConfig {

    static final String DEFAULT_ALLOWED_ORIGINS = "http://127.0.0.1:[*],http://localhost:[*]";

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, SocialOAuth2UserService users,
            AppLoginSuccessHandler success, ObjectProvider<ClientRegistrationRepository> registrations,
            Environment env) throws Exception {
        boolean devLogin = DevLogin.enabled(env);
        http.addFilterBefore(new SessionAbsoluteTimeoutFilter(Clock.systemUTC(), absoluteTimeout(env)), SecurityContextHolderFilter.class)
            .addFilterBefore(new OriginCheckFilter(allowedOrigins(env)), CsrfFilter.class)
            .authorizeHttpRequests(auth -> {
                auth.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                    .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/health", "/api/auth/csrf",
                            "/api/auth/providers", "/api/auth/config", "/auth/app/**", "/oauth2/**", "/login/oauth2/**").permitAll()
                    .requestMatchers(HttpMethod.HEAD, "/health", "/actuator/health", "/actuator/health/**").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/auth/app/exchange").permitAll();
                if (devLogin) {
                    auth.requestMatchers(HttpMethod.POST, "/dev/login").permitAll();
                }
                auth.requestMatchers("/api/admin/**").hasRole("ADMIN")
                    .requestMatchers("/api/**", "/ws", "/ws/**", "/livekit/**").authenticated()
                    .anyRequest().denyAll();
            })
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository(env))
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .ignoringRequestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/app/exchange")))
            .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .logout(logout -> logout.logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)));
        if (registrations.getIfAvailable() != null) {
            http.oauth2Login(oauth -> oauth.userInfoEndpoint(info -> info.userService(users)).successHandler(success));
        }
        return http.build();
    }

    /** XSRF-TOKEN 쿠키: 화면 JS 가 읽어야 하므로 HttpOnly 는 끄고, SameSite=Lax, Secure 는 세션 쿠키 설정을 따른다. */
    private static CookieCsrfTokenRepository csrfTokenRepository(Environment env) {
        boolean secure = env.getProperty("server.servlet.session.cookie.secure", Boolean.class, true);
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie.sameSite("Lax").secure(secure));
        return repository;
    }

    private static Duration absoluteTimeout(Environment env) {
        return SessionTimeouts.parseAbsolute(env.getProperty("app.session.absolute-timeout", "12h"));
    }

    private static List<String> allowedOrigins(Environment env) {
        return Arrays.stream(env.getProperty("app.allowed-origins", DEFAULT_ALLOWED_ORIGINS).split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
    }
}
