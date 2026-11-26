package com.my.tutipal_back.learning.focus.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.my.tutipal_back.learning.focus.FocusState;

/** PT-90 storage of focus samples (one per minute), the latest state per student and consents. */
public interface FocusStore {

    record Sample(Instant minuteAt, String exeName, String category, int idleSec, int switchCount) {
    }

    record Consent(String version, Instant consentedAt) {
    }

    /** state and updatedAt are null when the student has no state row. */
    record StudentState(long userId, FocusState state, Instant updatedAt) {
    }

    /** A minute already stored for (session, user) is kept (ON CONFLICT DO NOTHING). Returns inserted rows. */
    int insertSamples(long sessionId, long userId, List<Sample> samples);

    /** Samples with minuteAt >= since, oldest first. */
    List<Sample> recentSamples(long sessionId, long userId, Instant since);

    void upsertState(long sessionId, long userId, FocusState state, String modelVersion, Instant updatedAt);

    Optional<StudentState> state(long sessionId, long userId);

    /** Every current STUDENT participant of the session, with the state if there is one. */
    List<StudentState> studentStates(long sessionId);

    Optional<Consent> consent(long userId);

    /**
     * Reads the consent and keeps it locked against a withdrawal until the transaction ends (FOR SHARE). Writes use
     * this, so a withdrawal either waits for the write and then deletes its rows, or runs first and the write sees
     * no consent.
     */
    Optional<Consent> lockConsent(long userId);

    void saveConsent(long userId, String version, Instant consentedAt);

    /** Deletes the user's consent first (row lock, see lockConsent), then samples and states. */
    void deleteUserData(long userId);

    int deleteExpiredSamples(Instant endedBefore, int limit);

    int deleteExpiredStates(Instant endedBefore, int limit);

    /** Samples with minuteAt before the cutoff, in any session (ended or not). */
    int deleteStaleSamples(Instant minuteBefore, int limit);

    /** States not updated since the cutoff, in any session (ended or not). */
    int deleteStaleStates(Instant updatedBefore, int limit);
}
