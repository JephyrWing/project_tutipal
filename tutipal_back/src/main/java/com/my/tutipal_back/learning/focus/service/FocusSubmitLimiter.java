package com.my.tutipal_back.learning.focus.service;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.my.tutipal_back.learning.focus.FocusProperties;

/**
 * PT-90 AC-4: at most one accepted submit per (user, session) every app.focus.submit-min-interval, in server memory
 * (one instance; a restart forgets the last times, which only allows one early submit). A rejected attempt does not
 * move the time.
 */
@Component
public class FocusSubmitLimiter {

    private record Key(long userId, long sessionId) {
    }

    private final FocusProperties properties;
    private final Clock clock;
    private final ConcurrentMap<Key, Instant> lastAccepted = new ConcurrentHashMap<>();

    @Autowired
    public FocusSubmitLimiter(FocusProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public FocusSubmitLimiter(FocusProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public boolean tryAcquire(long userId, long sessionId) {
        Instant now = clock.instant();
        AtomicBoolean accepted = new AtomicBoolean(false);
        lastAccepted.compute(new Key(userId, sessionId), (key, last) -> {
            if (last == null || !now.isBefore(last.plus(properties.submitMinInterval()))) {
                accepted.set(true);
                return now;
            }
            return last;
        });
        return accepted.get();
    }

    /** Drops entries whose interval has passed (they would be accepted anyway). Returns the removed count. */
    public int evictExpired() {
        Instant cutoff = clock.instant().minus(properties.submitMinInterval());
        int before = lastAccepted.size();
        lastAccepted.values().removeIf(last -> !last.isAfter(cutoff));
        return before - lastAccepted.size();
    }

    public int size() {
        return lastAccepted.size();
    }

    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT5M")
    void scheduledEvict() {
        evictExpired();
    }
}
