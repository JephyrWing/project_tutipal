package com.my.tutipal_back.learning.focus.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import com.my.tutipal_back.learning.focus.client.FocusUnavailableException;
import com.my.tutipal_back.learning.focus.dto.FocusDtos.FocusStatusResponse;
import com.my.tutipal_back.learning.focus.dto.FocusDtos.FocusSubmitRequest;
import com.my.tutipal_back.learning.focus.dto.FocusDtos.Row;
import com.my.tutipal_back.learning.focus.dto.FocusDtos.StudentFocus;
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
 * PT-90 FocusService with the real StudySessionService (mocked repositories), the real FocusRecorder and
 * FocusSubmitLimiter on an in-memory store, a mocked FocusClient and a hand-moved clock.
 * AC-1 access, AC-2 consent gate, AC-4 submit interval, AC-5 server minutes, AC-6 prediction, AC-7 reading,
 * AC-8 teacher-only event.
 */
class FocusServiceTest {

    private static final long SESSION = 10L;
    private static final long TEACHER = 1L;
    private static final long STUDENT = 2L;
    private static final long OUTSIDER = 3L;
    private static final long STUDENT_B = 4L;
    private static final long STUDENT_C = 5L;
    private static final long OTHER_SESSION_TEACHER = 6L;
    /** 12:15:42 Thursday in Asia/Seoul. */
    private static final Instant NOW = Instant.parse("2026-11-26T03:15:42Z");
    private static final Instant MINUTE = Instant.parse("2026-11-26T03:15:00Z");

    private final FocusProperties properties = FocusProperties.defaults();
    private final MutableClock clock = new MutableClock(NOW);
    private final FakeFocusStore store = new FakeFocusStore();
    private StudySessionRepository sessions;
    private SessionParticipantRepository participants;
    private SessionEvents events;
    private FocusClient client;
    private StudySession session;
    private FocusService service;

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
        participant(TEACHER, SessionRole.TEACHER);
        participant(STUDENT, SessionRole.STUDENT);
        participant(STUDENT_B, SessionRole.STUDENT);
        participant(STUDENT_C, SessionRole.STUDENT);
        when(participants.findBySessionIdAndUserId(SESSION, OUTSIDER)).thenReturn(Optional.empty());
        when(participants.findBySessionIdAndUserId(SESSION, OTHER_SESSION_TEACHER)).thenReturn(Optional.empty());
        store.seedStudent(SESSION, STUDENT);
        store.seedStudent(SESSION, STUDENT_B);
        store.seedStudent(SESSION, STUDENT_C);
        store.seedConsent(STUDENT, properties.consentVersion(), NOW.minus(Duration.ofDays(1)));
        when(client.predict(any())).thenReturn(new FocusClient.Prediction(FocusState.FOCUS, "focus-lgbm-test"));

        FocusSubmitLimiter limiter = new FocusSubmitLimiter(properties, clock);
        FocusRecorder recorder = new FocusRecorder(sessionService, store, properties);
        service = new FocusService(sessionService, recorder, limiter, client, store, events, properties, clock);
    }

    private void participant(long userId, SessionRole role) {
        when(participants.findBySessionIdAndUserId(SESSION, userId))
                .thenReturn(Optional.of(new SessionParticipant(SESSION, userId, role)));
    }

    private static FocusSubmitRequest request(Row... rows) {
        return new FocusSubmitRequest(List.of(rows));
    }

    private static FocusSubmitRequest oneRow() {
        return request(new Row("code.exe", 3, 1));
    }

    private static void assertStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(status));
    }

    private FocusClient.Request sentRequest() {
        ArgumentCaptor<FocusClient.Request> captor = ArgumentCaptor.forClass(FocusClient.Request.class);
        verify(client).predict(captor.capture());
        return captor.getValue();
    }

    // ------------------------------------------------------------------ AC-1 access

    @Test
    void outsiderGets404AndNothingIsSaved() {
        assertStatus(() -> service.submit(OUTSIDER, SESSION, oneRow()), HttpStatus.NOT_FOUND);

        assertThat(store.sampleCount()).isZero();
        verify(client, never()).predict(any());
        verify(events, never()).toTeacher(anyLong(), anyString(), anyLong());
    }

    @Test
    void teacherGets403AndNothingIsSaved() {
        store.seedConsent(TEACHER, properties.consentVersion(), NOW);

        assertStatus(() -> service.submit(TEACHER, SESSION, oneRow()), HttpStatus.FORBIDDEN);

        assertThat(store.sampleCount()).isZero();
        verify(client, never()).predict(any());
    }

    @Test
    void endedSessionGets410AndNothingIsSaved() {
        session.end(NOW.minus(Duration.ofMinutes(1)));

        assertStatus(() -> service.submit(STUDENT, SESSION, oneRow()), HttpStatus.GONE);

        assertThat(store.sampleCount()).isZero();
        verify(client, never()).predict(any());
    }

    @Test
    void samplesAreSavedUnderTheCallersUserId() {
        service.submit(STUDENT, SESSION, oneRow());

        assertThat(store.samplesOf(SESSION, STUDENT)).hasSize(1);
        assertThat(store.sampleCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ AC-2 consent gate

    @Test
    void studentWithoutConsentGets403WithConsentMessageAndNothingIsSaved() {
        assertThatThrownBy(() -> service.submit(STUDENT_B, SESSION, oneRow()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getMessage()).contains("저장 동의가 필요해요");
                });

        assertThat(store.sampleCount()).isZero();
        verify(client, never()).predict(any());
    }

    @Test
    void consentForAnOldVersionIsNotEnough() {
        store.seedConsent(STUDENT_B, "2020-01-01", NOW.minus(Duration.ofDays(400)));

        assertStatus(() -> service.submit(STUDENT_B, SESSION, oneRow()), HttpStatus.FORBIDDEN);

        assertThat(store.sampleCount()).isZero();
    }

    // ------------------------------------------------------------------ AC-4 submit interval

    @Test
    void secondSubmitWithin50SecondsIs429AndSavesNothing() {
        service.submit(STUDENT, SESSION, request(new Row("code.exe", 3, 1)));
        clock.advance(Duration.ofMillis(49_900));

        assertStatus(() -> service.submit(STUDENT, SESSION, request(new Row("chrome.exe", 9, 4))),
                HttpStatus.TOO_MANY_REQUESTS);

        assertThat(store.samplesOf(SESSION, STUDENT)).extracting(FocusStore.Sample::exeName)
                .containsExactly("code.exe");
        verify(client, times(1)).predict(any());
    }

    @Test
    void submitAfter50SecondsIsAccepted() {
        service.submit(STUDENT, SESSION, oneRow());
        clock.advance(Duration.ofSeconds(50));

        FocusStatusResponse second = service.submit(STUDENT, SESSION, request(new Row("chrome.exe", 9, 4)));

        assertThat(second.state()).isEqualTo(FocusState.FOCUS);
        verify(client, times(2)).predict(any());
    }

    @Test
    void accessAndConsentAreCheckedBeforeTheInterval() {
        assertStatus(() -> service.submit(OUTSIDER, SESSION, oneRow()), HttpStatus.NOT_FOUND);
        assertStatus(() -> service.submit(OUTSIDER, SESSION, oneRow()), HttpStatus.NOT_FOUND);
        assertStatus(() -> service.submit(STUDENT_B, SESSION, oneRow()), HttpStatus.FORBIDDEN);
        assertStatus(() -> service.submit(STUDENT_B, SESSION, oneRow()), HttpStatus.FORBIDDEN);
    }

    @Test
    void rejectedSubmitDoesNotUseUpTheInterval() {
        assertStatus(() -> service.submit(STUDENT_B, SESSION, oneRow()), HttpStatus.FORBIDDEN);
        store.seedConsent(STUDENT_B, properties.consentVersion(), NOW);

        FocusStatusResponse accepted = service.submit(STUDENT_B, SESSION, oneRow());

        assertThat(accepted.state()).isEqualTo(FocusState.FOCUS);
    }

    // ------------------------------------------------------------------ AC-5 server minutes

    @Test
    void rowsGetServerMinutesEndingAtTheCurrentMinuteOldestFirst() {
        service.submit(STUDENT, SESSION, request(
                new Row("code.exe", 0, 0),
                new Row("chrome.exe", 60, 5),
                new Row(null, 12, 120)));

        List<FocusStore.Sample> saved = store.samplesOf(SESSION, STUDENT);
        assertThat(saved).extracting(FocusStore.Sample::minuteAt).containsExactly(
                MINUTE.minus(Duration.ofMinutes(2)), MINUTE.minus(Duration.ofMinutes(1)), MINUTE);
        assertThat(saved).extracting(FocusStore.Sample::exeName).containsExactly("code.exe", "chrome.exe", null);
        assertThat(saved).extracting(FocusStore.Sample::idleSec).containsExactly(0, 60, 12);
        assertThat(saved).extracting(FocusStore.Sample::switchCount).containsExactly(0, 5, 120);
    }

    @Test
    void categoryIsComputedOnTheServerFromTheExeName() {
        service.submit(STUDENT, SESSION, request(
                new Row("code.exe", 0, 0),
                new Row("chrome.exe", 0, 0),
                new Row("steam.exe", 0, 0),
                new Row(null, 0, 0)));

        assertThat(store.samplesOf(SESSION, STUDENT)).extracting(FocusStore.Sample::category)
                .containsExactly("ide", "browser", "game", "other");
    }

    @Test
    void fifteenRowsCoverTheLast15Minutes() {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            rows.add(new Row("code.exe", i, 0));
        }

        service.submit(STUDENT, SESSION, new FocusSubmitRequest(rows));

        List<FocusStore.Sample> saved = store.samplesOf(SESSION, STUDENT);
        assertThat(saved).hasSize(15);
        assertThat(saved.getFirst().minuteAt()).isEqualTo(MINUTE.minus(Duration.ofMinutes(14)));
        assertThat(saved.getLast().minuteAt()).isEqualTo(MINUTE);
    }

    @Test
    void aMinuteAlreadyStoredIsKeptAndTheSubmitStillSucceeds() {
        service.submit(STUDENT, SESSION, request(new Row("code.exe", 3, 1)));
        clock.advance(Duration.ofSeconds(50));   // 03:16:32 -> minutes 03:15 (again) and 03:16

        FocusStatusResponse second = service.submit(STUDENT, SESSION,
                request(new Row("chrome.exe", 9, 4), new Row("chrome.exe", 1, 0)));

        assertThat(second).isNotNull();
        List<FocusStore.Sample> saved = store.samplesOf(SESSION, STUDENT);
        assertThat(saved).hasSize(2);
        assertThat(saved.getFirst().minuteAt()).isEqualTo(MINUTE);
        assertThat(saved.getFirst().exeName()).isEqualTo("code.exe");
    }

    // ------------------------------------------------------------------ AC-6 prediction

    @Test
    void predictionSendsOnlyCategoryIdleSwitchAndMinutesAgoForTheLast15Minutes() {
        store.seedSample(SESSION, STUDENT, new FocusStore.Sample(MINUTE.minus(Duration.ofMinutes(15)), "old.exe",
                "other", 1, 1));   // 16 minutes before the target minute: outside the window
        store.seedSample(SESSION, STUDENT, new FocusStore.Sample(MINUTE.minus(Duration.ofMinutes(14)), "code.exe",
                "ide", 7, 2));
        store.seedSample(SESSION, OUTSIDER, new FocusStore.Sample(MINUTE, "steam.exe", "game", 0, 0));

        service.submit(STUDENT, SESSION, request(new Row("chrome.exe", 60, 3), new Row("code.exe", 5, 0)));

        FocusClient.Request sent = sentRequest();
        assertThat(sent.rows()).containsExactlyInAnyOrder(
                new FocusClient.Row(15, "ide", 7, 2),
                new FocusClient.Row(2, "browser", 60, 3),
                new FocusClient.Row(1, "ide", 5, 0));
    }

    @Test
    void hourAndWeekdayAreInTheConfiguredZone() {
        service.submit(STUDENT, SESSION, oneRow());

        FocusClient.Request sent = sentRequest();
        assertThat(sent.hour()).isCloseTo(12.26, within(0.02));
        assertThat(sent.weekday()).isEqualTo(3);   // Thursday, Monday = 0
    }

    @Test
    void weekdayFollowsSeoulDateNotUtcDate() {
        clock.set(Instant.parse("2026-11-26T15:30:10Z"));   // Friday 00:30 in Seoul, still Thursday in UTC

        service.submit(STUDENT, SESSION, oneRow());

        FocusClient.Request sent = sentRequest();
        assertThat(sent.weekday()).isEqualTo(4);
        assertThat(sent.hour()).isCloseTo(0.5, within(0.03));
    }

    @Test
    void okPredictionIsSavedAndReturned() {
        when(client.predict(any())).thenReturn(new FocusClient.Prediction(FocusState.DISTRACTED, "focus-lgbm-test"));

        FocusStatusResponse response = service.submit(STUDENT, SESSION, oneRow());

        assertThat(response.state()).isEqualTo(FocusState.DISTRACTED);
        assertThat(response.updatedAt()).isEqualTo(NOW);
        FakeFocusStore.StoredState saved = store.storedState(SESSION, STUDENT).orElseThrow();
        assertThat(saved.state()).isEqualTo(FocusState.DISTRACTED);
        assertThat(saved.modelVersion()).isEqualTo("focus-lgbm-test");
        assertThat(saved.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void breakPredictionIsReturned() {
        when(client.predict(any())).thenReturn(new FocusClient.Prediction(FocusState.BREAK, "v"));

        assertThat(service.submit(STUDENT, SESSION, oneRow()).state()).isEqualTo(FocusState.BREAK);
    }

    @Test
    void insufficientDataIsPending() {
        when(client.predict(any())).thenReturn(new FocusClient.Prediction(FocusState.PENDING, null));

        FocusStatusResponse response = service.submit(STUDENT, SESSION, oneRow());

        assertThat(response.state()).isEqualTo(FocusState.PENDING);
        assertThat(store.storedState(SESSION, STUDENT).orElseThrow().state()).isEqualTo(FocusState.PENDING);
    }

    @Test
    void unavailableModelServerIsUnknownAndTheSubmitStillSucceeds() {
        when(client.predict(any())).thenThrow(new FocusUnavailableException("status"));

        FocusStatusResponse response = service.submit(STUDENT, SESSION, oneRow());

        assertThat(response.state()).isEqualTo(FocusState.UNKNOWN);
        assertThat(store.samplesOf(SESSION, STUDENT)).hasSize(1);
        assertThat(store.storedState(SESSION, STUDENT).orElseThrow().state()).isEqualTo(FocusState.UNKNOWN);
    }

    @Test
    void unexpectedClientFailureIsAlsoUnknown() {
        when(client.predict(any())).thenThrow(new IllegalStateException("boom"));

        FocusStatusResponse response = service.submit(STUDENT, SESSION, oneRow());

        assertThat(response.state()).isEqualTo(FocusState.UNKNOWN);
        assertThat(store.samplesOf(SESSION, STUDENT)).hasSize(1);
    }

    // ------------------------------------------------------------------ AC-8 teacher-only event

    @Test
    void stateUpdateNotifiesOnlyTheTeacherChannelWithTheStudentId() {
        service.submit(STUDENT, SESSION, oneRow());

        verify(events).toTeacher(SESSION, "focus", STUDENT);
        verify(events, never()).toSession(anyLong(), anyString(), anyLong());
        verify(events, never()).toUser(anyLong(), anyString(), anyLong());
    }

    @Test
    void unknownStateIsAlsoAnnouncedToTheTeacher() {
        when(client.predict(any())).thenThrow(new FocusUnavailableException("timeout"));

        service.submit(STUDENT, SESSION, oneRow());

        verify(events).toTeacher(SESSION, "focus", STUDENT);
        verify(events, never()).toSession(anyLong(), anyString(), anyLong());
    }

    // ------------------------------------------------------------------ AC-7 reading

    @Test
    void teacherSeesEveryStudentWithFreshStatesAndNotParticipatingForMissingOrStale() {
        store.seedState(SESSION, STUDENT, FocusState.FOCUS, NOW.minus(Duration.ofSeconds(179)));
        store.seedState(SESSION, STUDENT_B, FocusState.BREAK, NOW.minus(Duration.ofSeconds(181)));

        List<StudentFocus> list = service.list(TEACHER, SESSION);

        assertThat(list).extracting(StudentFocus::userId).containsExactlyInAnyOrder(STUDENT, STUDENT_B, STUDENT_C);
        assertThat(list).filteredOn(s -> s.userId() == STUDENT).singleElement()
                .satisfies(s -> {
                    assertThat(s.state()).isEqualTo(FocusState.FOCUS);
                    assertThat(s.updatedAt()).isEqualTo(NOW.minus(Duration.ofSeconds(179)));
                });
        assertThat(list).filteredOn(s -> s.userId() == STUDENT_B).singleElement()
                .extracting(StudentFocus::state).isEqualTo(FocusState.NOT_PARTICIPATING);
        assertThat(list).filteredOn(s -> s.userId() == STUDENT_C).singleElement()
                .extracting(StudentFocus::state).isEqualTo(FocusState.NOT_PARTICIPATING);
    }

    @Test
    void teacherListDoesNotIncludeTheTeacher() {
        assertThat(service.list(TEACHER, SESSION)).extracting(StudentFocus::userId).doesNotContain(TEACHER);
    }

    @Test
    void studentCannotReadTheClassList() {
        assertStatus(() -> service.list(STUDENT, SESSION), HttpStatus.FORBIDDEN);
    }

    @Test
    void teacherOfAnotherSessionGets404() {
        assertStatus(() -> service.list(OTHER_SESSION_TEACHER, SESSION), HttpStatus.NOT_FOUND);
    }

    @Test
    void readingAnEndedSessionIs410() {
        session.end(NOW.minus(Duration.ofMinutes(1)));

        assertStatus(() -> service.list(TEACHER, SESSION), HttpStatus.GONE);
        assertStatus(() -> service.me(STUDENT, SESSION), HttpStatus.GONE);
    }

    @Test
    void studentReadsOwnFreshState() {
        store.seedState(SESSION, STUDENT, FocusState.DISTRACTED, NOW.minus(Duration.ofMinutes(1)));

        FocusStatusResponse me = service.me(STUDENT, SESSION);

        assertThat(me.state()).isEqualTo(FocusState.DISTRACTED);
        assertThat(me.updatedAt()).isEqualTo(NOW.minus(Duration.ofMinutes(1)));
    }

    @Test
    void studentWithoutStateOrWithStaleStateIsNotParticipating() {
        assertThat(service.me(STUDENT, SESSION).state()).isEqualTo(FocusState.NOT_PARTICIPATING);

        store.seedState(SESSION, STUDENT, FocusState.FOCUS, NOW.minus(Duration.ofMinutes(4)));

        assertThat(service.me(STUDENT, SESSION).state()).isEqualTo(FocusState.NOT_PARTICIPATING);
    }

    @Test
    void teacherCannotUseTheStudentEndpoint() {
        assertStatus(() -> service.me(TEACHER, SESSION), HttpStatus.FORBIDDEN);
    }

    @Test
    void outsiderCannotUseTheStudentEndpoint() {
        assertStatus(() -> service.me(OUTSIDER, SESSION), HttpStatus.NOT_FOUND);
    }

    @Test
    void stateSubmittedByTheStudentIsVisibleToTheTeacher() {
        when(client.predict(any())).thenReturn(new FocusClient.Prediction(FocusState.FOCUS, "v"));
        service.submit(STUDENT, SESSION, oneRow());

        List<StudentFocus> list = service.list(TEACHER, SESSION);

        assertThat(list).filteredOn(s -> s.userId() == STUDENT).singleElement()
                .extracting(StudentFocus::state).isEqualTo(FocusState.FOCUS);
    }
}
