package com.my.tutipal_back.learning.focus.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.my.tutipal_back.learning.focus.FocusState;

/**
 * PT-90 in-memory FocusStore for service tests. Mirrors the SQL rules that matter to the services:
 * one sample per (session, user, minute) with ON CONFLICT DO NOTHING, one state per (session, user),
 * studentStates lists every current STUDENT participant (state null when there is no row), and expiry only
 * touches sessions that ended before the cutoff.
 */
public class FakeFocusStore implements FocusStore {

    public record Key(long sessionId, long userId) {
    }

    public record StoredState(FocusState state, String modelVersion, Instant updatedAt) {
    }

    private final Map<Key, Map<Instant, Sample>> samples = new HashMap<>();
    private final Map<Key, StoredState> states = new LinkedHashMap<>();
    private final Map<Long, Consent> consents = new HashMap<>();
    private final Map<Long, Set<Long>> students = new HashMap<>();
    private final Map<Long, Instant> endedAt = new HashMap<>();
    public final List<String> calls = new ArrayList<>();
    /** Runs right after a write read the consent (race tests: something happens between the check and the insert). */
    public Runnable afterConsentRead = () -> { };

    // ------------------------------------------------------------------ seeding / inspection

    public void seedStudent(long sessionId, long userId) {
        students.computeIfAbsent(sessionId, k -> new LinkedHashSet<>()).add(userId);
    }

    public void seedEndedSession(long sessionId, Instant ended) {
        endedAt.put(sessionId, ended);
    }

    public void seedSample(long sessionId, long userId, Sample sample) {
        samples.computeIfAbsent(new Key(sessionId, userId), k -> new HashMap<>()).putIfAbsent(sample.minuteAt(), sample);
    }

    public void seedState(long sessionId, long userId, FocusState state, Instant updatedAt) {
        states.put(new Key(sessionId, userId), new StoredState(state, "seed", updatedAt));
    }

    public void seedConsent(long userId, String version, Instant at) {
        consents.put(userId, new Consent(version, at));
    }

    public List<Sample> samplesOf(long sessionId, long userId) {
        return samples.getOrDefault(new Key(sessionId, userId), Map.of()).values().stream()
                .sorted(Comparator.comparing(Sample::minuteAt)).toList();
    }

    public int sampleCount() {
        return samples.values().stream().mapToInt(Map::size).sum();
    }

    public Optional<StoredState> storedState(long sessionId, long userId) {
        return Optional.ofNullable(states.get(new Key(sessionId, userId)));
    }

    public int stateCount() {
        return states.size();
    }

    public boolean hasConsent(long userId) {
        return consents.containsKey(userId);
    }

    // ------------------------------------------------------------------ FocusStore

    @Override
    public int insertSamples(long sessionId, long userId, List<Sample> rows) {
        calls.add("insertSamples");
        Map<Instant, Sample> byMinute = samples.computeIfAbsent(new Key(sessionId, userId), k -> new HashMap<>());
        int inserted = 0;
        for (Sample s : rows) {
            if (byMinute.putIfAbsent(s.minuteAt(), s) == null) {
                inserted++;
            }
        }
        return inserted;
    }

    @Override
    public List<Sample> recentSamples(long sessionId, long userId, Instant since) {
        calls.add("recentSamples");
        return samplesOf(sessionId, userId).stream().filter(s -> !s.minuteAt().isBefore(since)).toList();
    }

    @Override
    public void upsertState(long sessionId, long userId, FocusState state, String modelVersion, Instant updatedAt) {
        calls.add("upsertState");
        states.put(new Key(sessionId, userId), new StoredState(state, modelVersion, updatedAt));
    }

    @Override
    public Optional<StudentState> state(long sessionId, long userId) {
        calls.add("state");
        StoredState s = states.get(new Key(sessionId, userId));
        return s == null ? Optional.empty() : Optional.of(new StudentState(userId, s.state(), s.updatedAt()));
    }

    @Override
    public List<StudentState> studentStates(long sessionId) {
        calls.add("studentStates");
        List<StudentState> out = new ArrayList<>();
        for (long userId : students.getOrDefault(sessionId, Set.of())) {
            StoredState s = states.get(new Key(sessionId, userId));
            out.add(new StudentState(userId, s == null ? null : s.state(), s == null ? null : s.updatedAt()));
        }
        return out;
    }

    @Override
    public Optional<Consent> consent(long userId) {
        calls.add("consent");
        return Optional.ofNullable(consents.get(userId));
    }

    @Override
    public Optional<Consent> lockConsent(long userId) {
        calls.add("lockConsent");
        Optional<Consent> consent = Optional.ofNullable(consents.get(userId));
        afterConsentRead.run();
        return consent;
    }

    @Override
    public void saveConsent(long userId, String version, Instant consentedAt) {
        calls.add("saveConsent");
        consents.put(userId, new Consent(version, consentedAt));
    }

    @Override
    public void deleteUserData(long userId) {
        calls.add("deleteUserData");
        samples.keySet().removeIf(k -> k.userId() == userId);
        states.keySet().removeIf(k -> k.userId() == userId);
        consents.remove(userId);
    }

    @Override
    public int deleteExpiredSamples(Instant endedBefore, int limit) {
        calls.add("deleteExpiredSamples");
        int deleted = 0;
        for (Map.Entry<Key, Map<Instant, Sample>> e : samples.entrySet()) {
            if (!expired(e.getKey().sessionId(), endedBefore)) {
                continue;
            }
            var it = e.getValue().keySet().iterator();
            while (it.hasNext() && deleted < limit) {
                it.next();
                it.remove();
                deleted++;
            }
        }
        samples.values().removeIf(Map::isEmpty);
        return deleted;
    }

    @Override
    public int deleteExpiredStates(Instant endedBefore, int limit) {
        calls.add("deleteExpiredStates");
        int deleted = 0;
        var it = states.keySet().iterator();
        while (it.hasNext() && deleted < limit) {
            if (expired(it.next().sessionId(), endedBefore)) {
                it.remove();
                deleted++;
            }
        }
        return deleted;
    }

    @Override
    public int deleteStaleSamples(Instant minuteBefore, int limit) {
        calls.add("deleteStaleSamples");
        int deleted = 0;
        for (Map<Instant, Sample> byMinute : samples.values()) {
            var it = byMinute.keySet().iterator();
            while (it.hasNext() && deleted < limit) {
                if (it.next().isBefore(minuteBefore)) {
                    it.remove();
                    deleted++;
                }
            }
        }
        samples.values().removeIf(Map::isEmpty);
        return deleted;
    }

    @Override
    public int deleteStaleStates(Instant updatedBefore, int limit) {
        calls.add("deleteStaleStates");
        int deleted = 0;
        var it = states.values().iterator();
        while (it.hasNext() && deleted < limit) {
            if (it.next().updatedAt().isBefore(updatedBefore)) {
                it.remove();
                deleted++;
            }
        }
        return deleted;
    }

    private boolean expired(long sessionId, Instant cutoff) {
        Instant ended = endedAt.get(sessionId);
        return ended != null && ended.isBefore(cutoff);
    }
}
