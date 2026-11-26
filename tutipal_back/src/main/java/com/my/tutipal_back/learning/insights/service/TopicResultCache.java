package com.my.tutipal_back.learning.insights.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.my.tutipal_back.learning.insights.dto.ConfusionsResponse;

/**
 * PT-87 D8: in-memory confusions result cache, at most 100 entries, 10 minute TTL. The key covers everything the
 * result depends on (session, newest question id, question count, concept set, embedding model), so a new question
 * or a changed tag set is a miss. Single server instance assumed (like AiQuota / AiSlots).
 * Stores only the response (labels and counts), never question text or student ids.
 */
@Component
public class TopicResultCache {

    static final int MAX_ENTRIES = 100;
    static final Duration TTL = Duration.ofMinutes(10);

    public record Key(long sessionId, long maxQuestionId, int questionCount, String conceptFingerprint, String model) {
    }

    private record Entry(ConfusionsResponse response, Instant expiresAt) {
    }

    private final Clock clock;
    /** Insertion order = age; guarded by this. */
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>();

    @Autowired
    public TopicResultCache() {
        this(Clock.systemUTC());
    }

    TopicResultCache(Clock clock) {
        this.clock = clock;
    }

    public synchronized Optional<ConfusionsResponse> get(Key key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (!clock.instant().isBefore(entry.expiresAt())) {
            entries.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.response());
    }

    public synchronized void put(Key key, ConfusionsResponse response) {
        Instant now = clock.instant();
        entries.remove(key);
        entries.put(key, new Entry(response, now.plus(TTL)));
        evictExpired(now);
        Iterator<Map.Entry<Key, Entry>> oldest = entries.entrySet().iterator();
        while (entries.size() > MAX_ENTRIES && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }

    private void evictExpired(Instant now) {
        entries.values().removeIf(e -> !now.isBefore(e.expiresAt()));
    }
}
