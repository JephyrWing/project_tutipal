package com.my.tutipal_back.learning.focus.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.my.tutipal_back.ai.quota.MutableClock;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.common.realtime.Presence;
import com.my.tutipal_back.common.realtime.SessionEvents;
import com.my.tutipal_back.learning.focus.FocusProperties;
import com.my.tutipal_back.learning.focus.FocusState;
import com.my.tutipal_back.learning.focus.client.FocusClient;
import com.my.tutipal_back.learning.focus.dto.FocusDtos.FocusSubmitRequest;
import com.my.tutipal_back.learning.focus.dto.FocusDtos.Row;
import com.my.tutipal_back.learning.focus.repository.FakeFocusStore;
import com.my.tutipal_back.learning.focus.repository.FocusStore;
import com.my.tutipal_back.sessions.entity.SessionParticipant;
import com.my.tutipal_back.sessions.entity.SessionRole;
import com.my.tutipal_back.sessions.entity.StudySession;
import com.my.tutipal_back.sessions.repository.SessionParticipantRepository;
import com.my.tutipal_back.sessions.repository.StudySessionRepository;
import com.my.tutipal_back.sessions.service.StudySessionService;
import com.my.tutipal_back.users.service.UserService;

/**
 * PT-90 submit racing with withdraw / leave / end. The model call runs outside any transaction, so these happen while
 * it waits: the state is then not stored and the teacher is not notified. Writes read the consent with lockConsent
 * (FOR SHARE in SQL) after the session lock, so a withdrawal is serialized with the write.
 */
class FocusSubmitRaceTest {

    private static final long SESSION = 10L;
    private static final long TEACHER = 1L;
    private static final long STUDENT = 2L;
    private static final Instant NOW = Instant.parse("2026-11-26T03:15:42Z");

    private final FocusProperties properties = FocusProperties.defaults();
    private final MutableClock clock = new MutableClock(NOW);
    private final FakeFocusStore store = new FakeFocusStore();
    private StudySessionRepository sessions;
    private SessionParticipantRepository participants;
    private SessionEvents events;
    private FocusClient client;
    private StudySession session;
    private FocusService service;
    private FocusConsentService consents;

    @BeforeEach
    void setUp() {
        sessions = mock(StudySessionRepository.class);
        participants = mock(SessionParticipantRepository.class);
        events = mock(SessionEvents.class);
        client = mock(FocusClient.class);
        StudySessionService sessionService = new StudySessionService(sessions, participants, mock(UserService.class),
                mock(SessionEvents.class), mock(Presence.class), mock(ApplicationEventPublisher.class));

        session = new StudySession("자바 기초", "자바", "ABC234", TEACHER);
        ReflectionTestUtils.setField(session, "id", SESSION);
        when(sessions.findById(SESSION)).thenReturn(Optional.of(session));
        when(sessions.lockById(SESSION)).thenReturn(Optional.of(session));
        when(participants.findBySessionIdAndUserId(SESSION, TEACHER))
                .thenReturn(Optional.of(new SessionParticipant(SESSION, TEACHER, SessionRole.TEACHER)));
        when(participants.findBySessionIdAndUserId(SESSION, STUDENT))
                .thenReturn(Optional.of(new SessionParticipant(SESSION, STUDENT, SessionRole.STUDENT)));
        store.seedStudent(SESSION, STUDENT);
        store.seedConsent(STUDENT, properties.consentVersion(), NOW.minus(Duration.ofDays(1)));

        FocusRecorder recorder = new FocusRecorder(sessionService, store, properties);
        service = new FocusService(sessionService, recorder, new FocusSubmitLimiter(properties, clock), client, store,
                events, properties, clock);
        consents = new FocusConsentService(store, properties, clock);
    }

    private static FocusSubmitRequest oneRow() {
        return new FocusSubmitRequest(List.of(new Row("code.exe", 3, 1)));
    }

    private void duringPrediction(Runnable action) {
        when(client.predict(any())).thenAnswer(invocation -> {
            action.run();
            return new FocusClient.Prediction(FocusState.FOCUS, "focus-lgbm-test");
        });
    }

    private void assertNoStateAndNoEvent() {
        assertThat(store.storedState(SESSION, STUDENT)).isEmpty();
        verify(events, never()).toTeacher(anyLong(), anyString(), anyLong());
    }

    private void assertRefused(HttpStatus status, String message) {
        assertThatThrownBy(() -> service.submit(STUDENT, SESSION, oneRow()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(status);
                    assertThat(e.getMessage()).contains(message);
                });
    }

    // Codex round 2: a state that could not be saved is the same refusal as a normal submit (the app then stops)

    @Test
    void withdrawWhileThePredictionRunsIs403ConsentRequiredWithoutStateOrEvent() {
        duringPrediction(() -> consents.withdraw(STUDENT));

        assertRefused(HttpStatus.FORBIDDEN, "저장 동의가 필요해요");

        assertNoStateAndNoEvent();
        assertThat(store.samplesOf(SESSION, STUDENT)).isEmpty();
        assertThat(store.hasConsent(STUDENT)).isFalse();
    }

    @Test
    void leavingWhileThePredictionRunsIs404WithoutStateOrEvent() {
        duringPrediction(() -> when(participants.findBySessionIdAndUserId(SESSION, STUDENT))
                .thenReturn(Optional.empty()));

        assertRefused(HttpStatus.NOT_FOUND, "방을 찾을 수 없어요");

        assertNoStateAndNoEvent();
    }

    @Test
    void sessionEndingWhileThePredictionRunsIs410WithoutStateOrEvent() {
        duringPrediction(() -> session.end(NOW));

        assertRefused(HttpStatus.GONE, "이미 닫힌 방이에요");

        assertNoStateAndNoEvent();
    }

    @Test
    void consentChangedToAnOldVersionWhileThePredictionRunsIs403ConsentRequiredWithoutState() {
        duringPrediction(() -> store.seedConsent(STUDENT, "2020-01-01", NOW));

        assertRefused(HttpStatus.FORBIDDEN, "저장 동의가 필요해요");

        assertNoStateAndNoEvent();
    }

    @Test
    void refusalTextsMatchTheChecksBeforeSaving() {
        assertThat(FocusService.refusal(FocusRecorder.SaveResult.NOT_STUDENT).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThatThrownBy(() -> FocusService.refusal(FocusRecorder.SaveResult.SAVED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withdrawCommittedBeforeTheLockedReadRefusesTheSamples() {
        StudySessionService sessionService = new StudySessionService(sessions, participants, mock(UserService.class),
                mock(SessionEvents.class), mock(Presence.class), mock(ApplicationEventPublisher.class));
        FocusRecorder recorder = new FocusRecorder(sessionService, store, properties);
        recorder.requireWritable(STUDENT, SESSION);
        consents.withdraw(STUDENT);

        assertThatThrownBy(() -> recorder.saveSamples(STUDENT, SESSION,
                List.of(new FocusStore.Sample(NOW, "code.exe", "ide", 1, 1))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(store.sampleCount()).isZero();
    }

    @Test
    void writesReadTheConsentWithTheLockAfterTheSessionLock() {
        when(client.predict(any())).thenReturn(new FocusClient.Prediction(FocusState.FOCUS, "v"));
        store.afterConsentRead = () -> verify(sessions, atLeastOnce()).lockById(SESSION);

        service.submit(STUDENT, SESSION, oneRow());

        // saveSamples and saveState each take the locked read; only the unlocked pre-check uses consent()
        assertThat(store.calls.stream().filter("lockConsent"::equals).count()).isEqualTo(2);
        assertThat(store.calls.indexOf("lockConsent")).isLessThan(store.calls.indexOf("insertSamples"));
        assertThat(store.calls.lastIndexOf("lockConsent")).isLessThan(store.calls.indexOf("upsertState"));
    }
}
