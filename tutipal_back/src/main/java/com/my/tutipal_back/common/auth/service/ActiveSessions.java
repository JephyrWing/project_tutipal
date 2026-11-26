package com.my.tutipal_back.common.auth.service;

import java.util.Arrays;
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
    /** 같은 계정의 로그인 전환을 한 번에 하나씩 (동시 로그인 두 건이 서로를 끊지 않게). 계정 id 로 고른 줄무늬 잠금. */
    private final Object[] loginLocks = new Object[256];

    public ActiveSessions() {
        Arrays.setAll(loginLocks, index -> new Object());
    }

    /**
     * 계정별로 직렬화해서 실행한다. 로그인은 이 안에서 "인증 저장 → 다른 세션 무효화"를 해야 한다. 동시에 들어온 두 로그인 중
     * 나중에 잠금을 얻은 쪽이 이기고, 먼저 끝난 쪽 세션은 끊긴다 (둘 다 끊기거나 둘 다 남지 않는다).
     */
    public void exclusiveForUser(Long userId, Runnable task) {
        synchronized (loginLocks[Math.floorMod(userId.hashCode(), loginLocks.length)]) {
            task.run();
        }
    }

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
        return invalidateUserExcept(userId, null);
    }

    /**
     * 한 계정당 기기 1대 (PM 결정 2026-11-26, PT-13): 새로 로그인한 세션(keepSessionId)만 남기고 같은 사용자의 다른 세션을
     * 무효화한다. 먼저 로그인한 기기의 STOMP·LiveKit 연결은 각 가드의 주기 검사가 닫는다.
     */
    public int invalidateUserExcept(Long userId, HttpSession keep) {
        String name = String.valueOf(userId);
        int count = 0;
        for (Map.Entry<String, HttpSession> entry : sessions.entrySet()) {
            try {
                if (!isSame(entry, keep) && name.equals(userOf(entry.getValue()))) {
                    entry.getValue().invalidate();
                    count++;
                }
            } catch (IllegalStateException alreadyInvalidated) {
                sessions.remove(entry.getKey(), entry.getValue());
            }
        }
        return count;
    }

    /** 세션 번호가 바뀐 직후(리스너 통지 전)에도 같은 세션으로 보도록 객체와 번호를 함께 본다. */
    private static boolean isSame(Map.Entry<String, HttpSession> entry, HttpSession keep) {
        if (keep == null) return false;
        if (entry.getValue() == keep) return true;
        try {
            return entry.getKey().equals(keep.getId());
        } catch (IllegalStateException invalidated) {
            return false;
        }
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
