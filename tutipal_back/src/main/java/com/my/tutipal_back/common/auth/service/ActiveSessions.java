package com.my.tutipal_back.common.auth.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;

import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * 서블릿 메모리 세션 목록 (Redis 없음 · 서버 재시작 시 비워진다). 회원 탈퇴 때 다른 기기에 남은 같은 사용자의 로그인 세션을
 * 바로 끊는 데 쓴다. 세션이 무효화되면 STOMP 가드(SessionWebSocketGuard)와 LiveKit 관문(LiveKitAccess)의 주기 검사가
 * 열린 연결도 닫는다. Spring Boot 가 이 빈을 서블릿 리스너로 등록한다.
 */
@Component
public class ActiveSessions implements HttpSessionListener, HttpSessionIdListener {

    private final Map<String, HttpSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void sessionCreated(HttpSessionEvent event) {
        sessions.put(event.getSession().getId(), event.getSession());
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        sessions.remove(event.getSession().getId());
    }

    /** 로그인 때 세션 번호를 바꾸면(세션 고정 방지) 새 번호로 옮긴다. */
    @Override
    public void sessionIdChanged(HttpSessionEvent event, String oldSessionId) {
        sessions.remove(oldSessionId);
        sessions.put(event.getSession().getId(), event.getSession());
    }

    /** 이 사용자로 로그인된 세션을 모두 무효화하고 끊은 수를 돌려준다. 이미 끝난 세션은 건너뛴다. */
    public int invalidateUser(Long userId) {
        String name = String.valueOf(userId);
        int count = 0;
        for (Map.Entry<String, HttpSession> entry : sessions.entrySet()) {
            try {
                if (name.equals(userOf(entry.getValue()))) {
                    entry.getValue().invalidate();
                    count++;
                }
            } catch (IllegalStateException alreadyInvalidated) {
                sessions.remove(entry.getKey(), entry.getValue());
            }
        }
        return count;
    }

    int size() {
        return sessions.size();
    }

    private static String userOf(HttpSession session) {
        Object context = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (context instanceof SecurityContext c && c.getAuthentication() != null && c.getAuthentication().isAuthenticated()) {
            return c.getAuthentication().getName();
        }
        return null;
    }
}
