package com.my.tutipal_back.learning.focus.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.common.realtime.SessionEvents;
import com.my.tutipal_back.learning.focus.FocusCategories;
import com.my.tutipal_back.learning.focus.FocusProperties;
import com.my.tutipal_back.learning.focus.FocusState;
import com.my.tutipal_back.learning.focus.client.FocusClient;
import com.my.tutipal_back.learning.focus.client.FocusUnavailableException;
import com.my.tutipal_back.learning.focus.dto.FocusDtos.FocusStatusResponse;
import com.my.tutipal_back.learning.focus.dto.FocusDtos.FocusSubmitRequest;
import com.my.tutipal_back.learning.focus.dto.FocusDtos.Row;
import com.my.tutipal_back.learning.focus.dto.FocusDtos.StudentFocus;
import com.my.tutipal_back.learning.focus.repository.FocusStore;
import com.my.tutipal_back.sessions.entity.SessionRole;
import com.my.tutipal_back.sessions.service.StudySessionService;

/**
 * PT-90 focus check: students send per-minute usage rows, the server stores them, asks FastAPI for a reference state
 * and shows it to the class teacher (badge per student) and to the student.
 *
 * <p>submit has no transaction of its own: access and consent are checked first (404 / 410 / 403), then the submit
 * interval (429), then the rows are saved in a short transaction ({@link FocusRecorder}), the model is called outside
 * any transaction (it may take up to app.focus.predict-timeout), and the state is saved in a second short
 * transaction. A failed prediction is UNKNOWN; the submit still succeeds.
 */
@Service
public class FocusService {

    private static final Logger log = LoggerFactory.getLogger(FocusService.class);
    /** Model window: the 15 minutes before the target minute. */
    static final int WINDOW_MINUTES = 15;

    private final StudySessionService sessions;
    private final FocusRecorder recorder;
    private final FocusSubmitLimiter limiter;
    private final FocusClient client;
    private final FocusStore store;
    private final SessionEvents events;
    private final FocusProperties properties;
    private final Clock clock;

    @Autowired
    public FocusService(StudySessionService sessions, FocusRecorder recorder, FocusSubmitLimiter limiter,
                        FocusClient client, FocusStore store, SessionEvents events, FocusProperties properties) {
        this(sessions, recorder, limiter, client, store, events, properties, Clock.systemUTC());
    }

    public FocusService(StudySessionService sessions, FocusRecorder recorder, FocusSubmitLimiter limiter,
                        FocusClient client, FocusStore store, SessionEvents events, FocusProperties properties,
                        Clock clock) {
        this.sessions = sessions;
        this.recorder = recorder;
        this.limiter = limiter;
        this.client = client;
        this.store = store;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    public FocusStatusResponse submit(long userId, long sessionId, FocusSubmitRequest request) {
        recorder.requireWritable(userId, sessionId);
        if (!limiter.tryAcquire(userId, sessionId)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "잠시 후에 다시 보내 주세요");
        }
        Instant now = clock.instant();
        Instant minute = now.truncatedTo(ChronoUnit.MINUTES);
        recorder.saveSamples(userId, sessionId, samples(request.rows(), minute));

        Instant since = minute.minus(Duration.ofMinutes(WINDOW_MINUTES - 1));
        List<FocusStore.Sample> recent = store.recentSamples(sessionId, userId, since);
        FocusClient.Prediction prediction = predict(recent, minute);

        FocusRecorder.SaveResult saved =
                recorder.saveState(userId, sessionId, prediction.state(), prediction.modelVersion(), now);
        if (saved != FocusRecorder.SaveResult.SAVED) {
            // left / ended / withdrew while the model ran: same answer as a refused submit, so the app stops
            throw refusal(saved);
        }
        events.toTeacher(sessionId, "focus", userId);
        return new FocusStatusResponse(prediction.state(), now);
    }

    @Transactional(readOnly = true)
    public List<StudentFocus> list(long userId, long sessionId) {
        if (sessions.requireActiveRole(userId, sessionId) != SessionRole.TEACHER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "이 방의 선생님만 볼 수 있어요");
        }
        Instant now = clock.instant();
        List<StudentFocus> out = new ArrayList<>();
        for (FocusStore.StudentState s : store.studentStates(sessionId)) {
            FocusState state = visible(s.state(), s.updatedAt(), now);
            out.add(new StudentFocus(s.userId(), state, state == FocusState.NOT_PARTICIPATING ? null : s.updatedAt()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public FocusStatusResponse me(long userId, long sessionId) {
        if (sessions.requireActiveRole(userId, sessionId) != SessionRole.STUDENT) {
            throw new ApiException(HttpStatus.FORBIDDEN, "학생만 볼 수 있어요");
        }
        Instant now = clock.instant();
        return store.state(sessionId, userId)
                .map(s -> {
                    FocusState state = visible(s.state(), s.updatedAt(), now);
                    return new FocusStatusResponse(state,
                            state == FocusState.NOT_PARTICIPATING ? null : s.updatedAt());
                })
                .orElseGet(() -> new FocusStatusResponse(FocusState.NOT_PARTICIPATING, null));
    }

    /** Rows are oldest first and end at the current server minute. */
    private static List<FocusStore.Sample> samples(List<Row> rows, Instant minute) {
        int n = rows.size();
        List<FocusStore.Sample> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Row row = rows.get(i);
            out.add(new FocusStore.Sample(minute.minus(Duration.ofMinutes(n - 1L - i)), row.exeName(),
                    FocusCategories.categorize(row.exeName()), row.idleSec(), row.switchCount()));
        }
        return out;
    }

    /**
     * The target is the minute after the latest stored one, so the latest minute is minutes_ago 1 and the window
     * covers minutes_ago 1..15. Only categories and numbers are sent.
     */
    private FocusClient.Prediction predict(List<FocusStore.Sample> recent, Instant minute) {
        List<FocusClient.Row> rows = new ArrayList<>(recent.size());
        for (FocusStore.Sample s : recent) {
            long ago = Duration.between(s.minuteAt(), minute).toMinutes() + 1;
            if (ago >= 1 && ago <= WINDOW_MINUTES) {
                rows.add(new FocusClient.Row((int) ago, s.category(), s.idleSec(), s.switchCount()));
            }
        }
        // same as training: local hour + minute / 60 of the target minute, Monday = 0
        ZonedDateTime target = minute.plus(Duration.ofMinutes(1)).atZone(properties.zone());
        double hour = target.getHour() + target.getMinute() / 60.0;
        int weekday = target.getDayOfWeek().getValue() - 1;
        long started = System.nanoTime();
        try {
            return client.predict(new FocusClient.Request(rows, hour, weekday));
        } catch (FocusUnavailableException e) {
            log.warn("focus prediction unavailable: reason={} ms={}", e.getMessage(), elapsedMs(started));
        } catch (RuntimeException e) {
            log.warn("focus prediction failed: reason={} ms={}", e.getClass().getSimpleName(), elapsedMs(started));
        }
        return new FocusClient.Prediction(FocusState.UNKNOWN, null);
    }

    /** Same status and text as the checks before saving (StudySessionService 404 / 410, FocusRecorder 403). */
    static ApiException refusal(FocusRecorder.SaveResult result) {
        return switch (result) {
            case NOT_PARTICIPANT -> new ApiException(HttpStatus.NOT_FOUND, "방을 찾을 수 없어요");
            case ENDED -> new ApiException(HttpStatus.GONE, "이미 닫힌 방이에요");
            case NOT_STUDENT -> new ApiException(HttpStatus.FORBIDDEN, FocusRecorder.STUDENT_ONLY);
            case NO_CONSENT -> new ApiException(HttpStatus.FORBIDDEN, FocusRecorder.CONSENT_REQUIRED);
            case SAVED -> throw new IllegalArgumentException("saved is not a refusal");
        };
    }

    private FocusState visible(FocusState state, Instant updatedAt, Instant now) {
        if (state == null || updatedAt == null || updatedAt.plus(properties.staleAfter()).isBefore(now)) {
            return FocusState.NOT_PARTICIPATING;
        }
        return state;
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
