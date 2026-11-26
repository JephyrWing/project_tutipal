package com.my.tutipal_back.learning.focus.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.learning.focus.FocusProperties;
import com.my.tutipal_back.learning.focus.FocusState;
import com.my.tutipal_back.learning.focus.repository.FocusStore;
import com.my.tutipal_back.sessions.entity.SessionRole;
import com.my.tutipal_back.sessions.service.StudySessionService;

/**
 * PT-90 transactional writes of FocusService. Saving takes the session row lock first (same order as leave, kick
 * and end), then checks again that the caller is a STUDENT of a running session with the current consent, so a
 * submit racing with leave / kick / end does not store rows for someone who is no longer in the class. The consent
 * is read with {@link FocusStore#lockConsent} (FOR SHARE) so a withdrawal racing with the write either waits and then
 * deletes the new rows, or runs first and the write is refused.
 */
@Component
public class FocusRecorder {

    static final String CONSENT_REQUIRED = "저장 동의가 필요해요";
    static final String STUDENT_ONLY = "학생만 집중도 기록을 보낼 수 있어요";

    /** Outcome of saveState; anything but SAVED stored nothing and FocusService answers like a normal refusal. */
    public enum SaveResult {
        SAVED, NOT_PARTICIPANT, ENDED, NOT_STUDENT, NO_CONSENT
    }

    private final StudySessionService sessions;
    private final FocusStore store;
    private final FocusProperties properties;

    public FocusRecorder(StudySessionService sessions, FocusStore store, FocusProperties properties) {
        this.sessions = sessions;
        this.store = store;
        this.properties = properties;
    }

    /** Access and consent check without the lock (before the submit interval is used). 404 / 410 / 403. */
    @Transactional(readOnly = true)
    public void requireWritable(long userId, long sessionId) {
        requireStudentWithConsent(userId, sessionId, false);
    }

    @Transactional
    public void saveSamples(long userId, long sessionId, List<FocusStore.Sample> samples) {
        sessions.lockForHelp(sessionId);
        requireStudentWithConsent(userId, sessionId, true);
        store.insertSamples(sessionId, userId, samples);
    }

    /**
     * Saves the state unless the student left, the session ended or consent was withdrawn meanwhile; then returns
     * why. Non-throwing checks: an ApiException from a joined transactional call would mark this transaction
     * rollback-only, so the caller throws outside the transaction.
     */
    @Transactional
    public SaveResult saveState(long userId, long sessionId, FocusState state, String modelVersion, Instant now) {
        sessions.lockForHelp(sessionId);
        Optional<SessionRole> participant = sessions.participantRole(userId, sessionId);
        if (participant.isEmpty()) {
            return SaveResult.NOT_PARTICIPANT;
        }
        if (sessions.roleOf(userId, sessionId).isEmpty()) {
            return SaveResult.ENDED;
        }
        if (participant.get() != SessionRole.STUDENT) {
            return SaveResult.NOT_STUDENT;
        }
        if (!hasCurrentConsent(userId, true)) {
            return SaveResult.NO_CONSENT;
        }
        store.upsertState(sessionId, userId, state, modelVersion, now);
        return SaveResult.SAVED;
    }

    private void requireStudentWithConsent(long userId, long sessionId, boolean lock) {
        if (sessions.requireActiveRole(userId, sessionId) != SessionRole.STUDENT) {
            throw new ApiException(HttpStatus.FORBIDDEN, STUDENT_ONLY);
        }
        if (!hasCurrentConsent(userId, lock)) {
            throw new ApiException(HttpStatus.FORBIDDEN, CONSENT_REQUIRED);
        }
    }

    private boolean hasCurrentConsent(long userId, boolean lock) {
        Optional<FocusStore.Consent> consent = lock ? store.lockConsent(userId) : store.consent(userId);
        return consent.filter(c -> properties.consentVersion().equals(c.version())).isPresent();
    }
}
