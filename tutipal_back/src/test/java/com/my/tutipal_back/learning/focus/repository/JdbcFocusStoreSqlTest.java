package com.my.tutipal_back.learning.focus.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.jdbc.core.JdbcTemplate;

import com.my.tutipal_back.learning.focus.FocusState;

/**
 * PT-90 SQL shape of JdbcFocusStore without a database (real Postgres is not run in JUnit):
 * AC-5 same minute ON CONFLICT DO NOTHING, AC-6 the 15-minute read, AC-7 one row per current STUDENT,
 * AC-2 consent upsert and the three-table delete, AC-9 batched expiry by ended sessions.
 */
class JdbcFocusStoreSqlTest {

    private static final Instant T = Instant.parse("2026-11-26T03:15:00Z");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JdbcFocusStore store = new JdbcFocusStore(jdbc);

    /** Runs the call (a mock JdbcTemplate may make the mapping fail; only the SQL sent matters here). */
    private void quietly(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException ignored) {
            // the mock returns null / empty results
        }
    }

    private List<Invocation> invocations() {
        return new ArrayList<>(mockingDetails(jdbc).getInvocations());
    }

    private List<String> sql() {
        List<String> out = new ArrayList<>();
        for (Invocation i : invocations()) {
            for (Object arg : i.getRawArguments()) {
                if (arg instanceof String s
                        && s.toLowerCase(Locale.ROOT).matches("(?s)\\s*(select|insert|update|delete|with)\\b.*")) {
                    out.add(s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " "));
                }
            }
        }
        return out;
    }

    private List<Object> args() {
        List<Object> out = new ArrayList<>();
        for (Invocation i : invocations()) {
            for (Object arg : i.getRawArguments()) {
                if (arg instanceof Object[] array) {
                    out.addAll(Arrays.asList(array));
                } else {
                    out.add(arg);
                }
            }
        }
        return out;
    }

    @Test
    void insertSamplesIgnoresAMinuteAlreadyStored() {
        quietly(() -> store.insertSamples(10L, 2L, List.of(new FocusStore.Sample(T, "code.exe", "ide", 3, 1))));

        assertThat(sql()).anySatisfy(s -> assertThat(s).contains("insert into focus_samples")
                .contains("minute_at").contains("exe_name").contains("category").contains("idle_sec")
                .contains("switch_count").contains("on conflict").contains("do nothing"));
    }

    @Test
    void recentSamplesReadsOneUserInOneSessionSinceTheGivenMinute() {
        quietly(() -> store.recentSamples(10L, 2L, T));

        assertThat(sql()).anySatisfy(s -> assertThat(s).contains("from focus_samples").contains("session_id")
                .contains("user_id").contains("minute_at >=").contains("order by minute_at"));
        assertThat(args()).contains(10L, 2L);
    }

    @Test
    void upsertStateReplacesTheRowOfThatStudent() {
        quietly(() -> store.upsertState(10L, 2L, FocusState.FOCUS, "v1", T));

        assertThat(sql()).anySatisfy(s -> assertThat(s).contains("insert into focus_states")
                .contains("on conflict (session_id, user_id) do update"));
        assertThat(args()).contains(10L, 2L, "FOCUS", "v1");
    }

    @Test
    void stateReadsOneStudent() {
        quietly(() -> store.state(10L, 2L));

        assertThat(sql()).anySatisfy(s -> assertThat(s).contains("from focus_states").contains("session_id")
                .contains("user_id"));
        assertThat(args()).contains(10L, 2L);
    }

    @Test
    void studentStatesListsEveryCurrentStudentParticipantWithItsStateIfAny() {
        quietly(() -> store.studentStates(10L));

        List<String> sent = sql();
        assertThat(sent).anySatisfy(s -> assertThat(s).contains("session_participants").contains("left join focus_states")
                .contains("role"));
        boolean studentFilter = sent.stream().anyMatch(s -> s.contains("'student'")) || args().contains("STUDENT");
        assertThat(studentFilter).as("filters role = STUDENT").isTrue();
        assertThat(args()).contains(10L);
    }

    @Test
    void consentReadsByUser() {
        quietly(() -> store.consent(2L));

        assertThat(sql()).anySatisfy(s -> assertThat(s).contains("from focus_consents").contains("user_id"));
        assertThat(args()).contains(2L);
    }

    @Test
    void saveConsentUpsertsVersionAndTime() {
        quietly(() -> store.saveConsent(2L, "2026-11-26", T));

        assertThat(sql()).anySatisfy(s -> assertThat(s).contains("insert into focus_consents").contains("version")
                .contains("consented_at").contains("on conflict (user_id) do update"));
        assertThat(args()).contains(2L, "2026-11-26");
    }

    @Test
    void deleteUserDataRemovesSamplesStatesAndConsent() {
        quietly(() -> store.deleteUserData(2L));

        List<String> sent = sql();
        assertThat(sent).anySatisfy(s -> assertThat(s).startsWith("delete from focus_samples").contains("user_id"));
        assertThat(sent).anySatisfy(s -> assertThat(s).startsWith("delete from focus_states").contains("user_id"));
        assertThat(sent).anySatisfy(s -> assertThat(s).startsWith("delete from focus_consents").contains("user_id"));
        assertThat(args()).contains(2L);
    }

    @Test
    void expiredSamplesAreDeletedInLimitedBatchesOfEndedSessions() {
        quietly(() -> store.deleteExpiredSamples(T, 500));

        assertThat(sql()).anySatisfy(s -> assertThat(s).startsWith("delete from focus_samples")
                .contains("study_sessions").contains("ended_at <").contains("limit"));
        assertThat(args()).contains(500);
    }

    @Test
    void expiredStatesAreDeletedInLimitedBatchesOfEndedSessions() {
        quietly(() -> store.deleteExpiredStates(T, 500));

        assertThat(sql()).anySatisfy(s -> assertThat(s).startsWith("delete from focus_states")
                .contains("study_sessions").contains("ended_at <").contains("limit"));
        assertThat(args()).contains(500);
    }

    @Test
    void lockConsentReadsTheRowForShare() {
        quietly(() -> store.lockConsent(2L));

        assertThat(sql()).anySatisfy(s -> assertThat(s).contains("from focus_consents").contains("user_id")
                .endsWith("for share"));
        assertThat(args()).contains(2L);
    }

    @Test
    void deleteUserDataDeletesTheConsentFirst() {
        quietly(() -> store.deleteUserData(2L));

        assertThat(sql().getFirst()).startsWith("delete from focus_consents");
    }

    @Test
    void staleSamplesAreDeletedByMinuteInLimitedBatchesInAnySession() {
        quietly(() -> store.deleteStaleSamples(T, 500));

        assertThat(sql()).anySatisfy(s -> assertThat(s).startsWith("delete from focus_samples")
                .contains("minute_at <").contains("limit").doesNotContain("study_sessions"));
        assertThat(args()).contains(500);
    }

    @Test
    void staleStatesAreDeletedByUpdateTimeInLimitedBatches() {
        quietly(() -> store.deleteStaleStates(T, 500));

        assertThat(sql()).anySatisfy(s -> assertThat(s).startsWith("delete from focus_states")
                .contains("updated_at <").contains("limit"));
        assertThat(args()).contains(500);
    }
}
