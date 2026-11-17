package com.my.tutipal_back.common.config;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import com.my.tutipal_back.common.auth.service.AppLoginSuccessHandler;
import com.my.tutipal_back.users.service.SocialOAuth2UserService;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, SocialOAuth2UserService users,
            AppLoginSuccessHandler success, ObjectProvider<ClientRegistrationRepository> registrations) throws Exception {
        http.addFilterBefore(new SessionAbsoluteTimeoutFilter(Clock.systemUTC()), SecurityContextHolderFilter.class)
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**", "/health", "/api/auth/csrf",
                        "/api/auth/providers", "/api/auth/config", "/api/auth/app/exchange", "/auth/app/**", "/oauth2/**", "/login/oauth2/**", "/dev/login").permitAll()
                .requestMatchers("/api/**", "/ws", "/ws/**", "/livekit/**").authenticated()
                .anyRequest().denyAll())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .ignoringRequestMatchers("/api/auth/app/exchange"))
            .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .logout(logout -> logout.logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)));
        if (registrations.getIfAvailable() != null) {
            http.oauth2Login(oauth -> oauth.userInfoEndpoint(info -> info.userService(users)).successHandler(success));
        }
        return http.build();
    }
}
