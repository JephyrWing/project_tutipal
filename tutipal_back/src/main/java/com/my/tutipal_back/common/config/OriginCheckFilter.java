package com.my.tutipal_back.common.config;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 상태를 바꾸는 요청(GET/HEAD/OPTIONS/TRACE 외)에 Origin 헤더가 있으면 허용 목록(app.allowed-origins)과 맞는지 본다.
 * 맞지 않으면 403. Origin 이 없으면(앱·서버 간 호출) 통과시키고 CSRF 검사에 맡긴다. 패턴은 WebSocket 과 같다.
 * SecurityConfig 에서 CSRF 필터 앞에 넣는다 (Bean 이 아님: 서블릿 필터로 두 번 등록되지 않게).
 */
public class OriginCheckFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final CorsConfiguration origins = new CorsConfiguration();

    public OriginCheckFilter(List<String> allowedOriginPatterns) {
        origins.setAllowedOriginPatterns(allowedOriginPatterns);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !SAFE_METHODS.contains(request.getMethod()) && origins.checkOrigin(origin) == null) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        chain.doFilter(request, response);
    }
}
