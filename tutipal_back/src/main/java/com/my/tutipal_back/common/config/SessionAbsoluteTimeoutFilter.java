package com.my.tutipal_back.common.config;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 세션 절대 제한: 계속 쓰고 있어도 만든 지 app.session.absolute-timeout(기본 12시간)이 지나면 버린다
 * (유휴 제한은 server.servlet.session.timeout, 기본 2시간).
 * 버린 뒤 요청은 비로그인으로 처리돼서 API 는 401 → 화면이 로그인으로 보낸다.
 * SecurityConfig 에서 보안 필터 체인 맨 앞에 넣는다 (Bean 이 아님: 서블릿 필터로 두 번 등록되지 않게).
 */
public class SessionAbsoluteTimeoutFilter extends OncePerRequestFilter {

    private final Clock clock;
    private final Duration absoluteTimeout;

    public SessionAbsoluteTimeoutFilter(Clock clock, Duration absoluteTimeout) {
        this.clock = clock;
        this.absoluteTimeout = absoluteTimeout;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        // changeSessionId(로그인 시 번호 교체)는 만든 시각을 유지하므로 첫 세션 기준
        if (session != null && clock.millis() - session.getCreationTime() > absoluteTimeout.toMillis()) {
            session.invalidate();
        }
        chain.doFilter(request, response);
    }
}
