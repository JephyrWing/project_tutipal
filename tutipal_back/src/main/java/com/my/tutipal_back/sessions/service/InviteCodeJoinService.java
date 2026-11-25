package com.my.tutipal_back.sessions.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.sessions.dto.SessionResponse;

/** 인증된 계정의 초대 코드 실패를 단일 Spring 프로세스에서 제한한다. */
@Service
public class InviteCodeJoinService {

    private static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(5);

    private final StudySessionService sessions;
    private final Clock clock;
    private final ConcurrentHashMap<Long, FailureWindow> failures = new ConcurrentHashMap<>();
    private final Object[] accountLocks = new Object[256];

    @Autowired
    public InviteCodeJoinService(StudySessionService sessions) {
        this(sessions, Clock.systemUTC());
    }

    InviteCodeJoinService(StudySessionService sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
        Arrays.setAll(accountLocks, index -> new Object());
    }

    public SessionResponse join(Long userId, String code) {
        Object lock = accountLocks[Math.floorMod(userId.hashCode(), accountLocks.length)];
        // 같은 계정의 한도 확인, 참여 트랜잭션, 실패 집계를 한 번에 직렬화한다.
        synchronized (lock) {
            Instant now = clock.instant();
            FailureWindow window = failures.get(userId);
            if (window != null && now.isBefore(window.expiresAt()) && window.count() >= MAX_FAILURES) {
                throw new RetryLater(Duration.between(now, window.expiresAt()));
            }
            try {
                return sessions.join(userId, code);
            } catch (ApiException error) {
                if (error.getStatus() == HttpStatus.NOT_FOUND || error.getStatus() == HttpStatus.GONE) {
                    recordFailure(userId, window, clock.instant());
                }
                throw error;
            }
        }
    }

    private void recordFailure(Long userId, FailureWindow window, Instant now) {
        if (window == null || !now.isBefore(window.expiresAt())) {
            failures.put(userId, new FailureWindow(1, now.plus(WINDOW)));
            return;
        }
        failures.put(userId, new FailureWindow(window.count() + 1, window.expiresAt()));
    }

    @Scheduled(fixedDelay = 60_000)
    void discardExpiredWindows() {
        Instant now = clock.instant();
        failures.forEach((userId, window) -> {
            if (!now.isBefore(window.expiresAt())) {
                failures.remove(userId, window);
            }
        });
    }

    private record FailureWindow(int count, Instant expiresAt) {
    }

    public static final class RetryLater extends ApiException {
        private final long retryAfterSeconds;

        RetryLater(Duration remaining) {
            super(HttpStatus.TOO_MANY_REQUESTS, "초대 코드 시도 횟수를 초과했어요. 잠시 후 다시 시도해 주세요.");
            retryAfterSeconds = remaining.toSeconds() + (remaining.getNano() == 0 ? 0 : 1);
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}
