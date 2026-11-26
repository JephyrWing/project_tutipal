package com.my.tutipal_back.learning.focus.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.learning.focus.FocusProperties;
import com.my.tutipal_back.learning.focus.FocusState;
import com.my.tutipal_back.learning.focus.service.FocusRecorder;
import com.my.tutipal_back.learning.focus.service.FocusRetention;
import com.my.tutipal_back.sessions.entity.SessionRole;
import com.my.tutipal_back.sessions.service.StudySessionService;
import com.my.tutipal_back.users.repository.AccountCleanup;
import com.my.tutipal_back.users.repository.AccountLocks;

/**
 * PT-90: dedicated Gradle task focusDbTest only (excluded from test). Disposable loopback PostgreSQL, synthetic data,
 * no env files or production DB. Missing env fails instead of skipping (CI also fails when nothing ran).
 * Checks V23 constraints (FK CASCADE, UNIQUE, CHECK), ON CONFLICT and the 15-minute read, the consent row lock
 * (FOR SHARE vs withdrawal, both orders), account withdrawal (anonymize, rows kept) racing consent withdrawal
 * without deadlock, and retention.
 */
class FocusStorePostgresTest {

    private static final long TEACHER = 9001L;
    private static final long STUDENT = 9002L;
    private static final long OTHER = 9003L;
    private static final long SESSION = 90001L;
    private static final long ENDED_OLD = 90002L;
    private static final long ENDED_RECENT = 90003L;
    private static final String VERSION = FocusProperties.defaults().consentVersion();
    private static final Instant MINUTE = Instant.now().truncatedTo(ChronoUnit.MINUTES);

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static ExecutorService pool;
    private JdbcFocusStore store;

    @BeforeAll
    static void prepareDisposableDatabase() {
        String url = System.getenv("FOCUS_TEST_DB_URL");
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/tutipal_focus_e2e")
                || !"disposable".equals(System.getenv("FOCUS_TEST_DB_ALLOW_RESET"))) {
            throw new IllegalStateException("Provide an explicit loopback tutipal_focus_e2e disposable database");
        }
        String password = System.getenv("FOCUS_TEST_DB_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("Missing disposable DB password");
        }
        dataSource = new DriverManagerDataSource(url, "tutipal_focus_e2e", password);
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        pool = Executors.newFixedThreadPool(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version = '23' AND success",
                Long.class)).isEqualTo(1L);
    }

    @AfterAll
    static void stopPool() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM focus_samples");
        jdbc.update("DELETE FROM focus_states");
        jdbc.update("DELETE FROM focus_consents");
        jdbc.update("DELETE FROM session_participants WHERE user_id IN (?,?,?)", TEACHER, STUDENT, OTHER);
        jdbc.update("DELETE FROM study_sessions WHERE owner_id IN (?,?,?)", TEACHER, STUDENT, OTHER);
        jdbc.update("DELETE FROM users WHERE id IN (?,?,?)", TEACHER, STUDENT, OTHER);
        for (long user : List.of(TEACHER, STUDENT, OTHER)) {
            jdbc.update("INSERT INTO users(id,created_at,updated_at,name,provider,provider_id,role)"
                    + " VALUES (?,now(),now(),'synthetic','dev',?,'USER')", user, "focus-" + user);
        }
        session(SESSION, null);
        session(ENDED_OLD, Instant.now().minus(Duration.ofDays(31)));
        session(ENDED_RECENT, Instant.now().minus(Duration.ofDays(29)));
        jdbc.update("INSERT INTO focus_consents(user_id,version,consented_at) VALUES (?,?,now()),(?,?,now())",
                STUDENT, VERSION, OTHER, VERSION);
        store = new JdbcFocusStore(jdbc);
    }

    private static void session(long id, Instant endedAt) {
        jdbc.update("INSERT INTO study_sessions(id,created_at,updated_at,title,topic,invite_code,owner_id,ended_at)"
                + " VALUES (?,now(),now(),'focus e2e','java',?,?,?)", id, "F" + (id % 100000), TEACHER,
                endedAt == null ? null : endedAt.atOffset(ZoneOffset.UTC));
        jdbc.update("INSERT INTO session_participants(created_at,updated_at,session_id,user_id,role)"
                + " VALUES (now(),now(),?,?,'TEACHER'),(now(),now(),?,?,'STUDENT'),(now(),now(),?,?,'STUDENT')",
                id, TEACHER, id, STUDENT, id, OTHER);
    }

    private static FocusStore.Sample sample(Instant minute) {
        return new FocusStore.Sample(minute, "code.exe", "ide", 3, 1);
    }

    private static long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    /** FocusRecorder on the real store, with the session checks stubbed (student of a running session). */
    private static FocusRecorder recorder(FocusStore focusStore) {
        StudySessionService sessions = mock(StudySessionService.class);
        when(sessions.requireActiveRole(anyLong(), anyLong())).thenReturn(SessionRole.STUDENT);
        when(sessions.participantRole(anyLong(), anyLong())).thenReturn(Optional.of(SessionRole.STUDENT));
        when(sessions.roleOf(anyLong(), anyLong())).thenReturn(Optional.of(SessionRole.STUDENT));
        return new FocusRecorder(sessions, focusStore, FocusProperties.defaults());
    }

    // ------------------------------------------------------------------ V23 constraints

    @Test
    void dbRejectsAPathAsExeAndOutOfRangeValues() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO focus_samples(session_id,user_id,minute_at,exe_name,category,"
                + "idle_sec,switch_count) VALUES (?,?,now(),'c:\\windows\\code.exe','ide',1,1)", SESSION, STUDENT))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO focus_samples(session_id,user_id,minute_at,exe_name,category,"
                + "idle_sec,switch_count) VALUES (?,?,now(),'code.exe','ide',61,1)", SESSION, STUDENT))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO focus_samples(session_id,user_id,minute_at,exe_name,category,"
                + "idle_sec,switch_count) VALUES (?,?,now(),'code.exe','secret',1,1)", SESSION, STUDENT))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO focus_states(session_id,user_id,state,updated_at)"
                + " VALUES (?,?,'NOT_PARTICIPATING',now())", SESSION, STUDENT))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("focus_samples")).isZero();
    }

    @Test
    void sameMinuteIsUniqueAndInsertSamplesKeepsTheFirst() {
        assertThat(store.insertSamples(SESSION, STUDENT, List.of(sample(MINUTE)))).isEqualTo(1);
        assertThat(store.insertSamples(SESSION, STUDENT,
                List.of(new FocusStore.Sample(MINUTE, "chrome.exe", "browser", 9, 9), sample(MINUTE.plusSeconds(60)))))
                .isEqualTo(1);

        assertThat(store.recentSamples(SESSION, STUDENT, MINUTE)).extracting(FocusStore.Sample::exeName)
                .containsExactly("code.exe", "code.exe");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO focus_samples(session_id,user_id,minute_at,exe_name,category,"
                + "idle_sec,switch_count) VALUES (?,?,?,'code.exe','ide',1,1)", SESSION, STUDENT,
                MINUTE.atOffset(ZoneOffset.UTC))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void recentSamplesReadsOnlyFromTheGivenMinuteForThatUserAndSession() {
        List<FocusStore.Sample> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            rows.add(sample(MINUTE.minus(Duration.ofMinutes(i))));
        }
        store.insertSamples(SESSION, STUDENT, rows);
        store.insertSamples(SESSION, OTHER, List.of(sample(MINUTE)));
        store.insertSamples(ENDED_RECENT, STUDENT, List.of(sample(MINUTE)));

        List<FocusStore.Sample> recent = store.recentSamples(SESSION, STUDENT, MINUTE.minus(Duration.ofMinutes(14)));

        assertThat(recent).hasSize(15);
        assertThat(recent.getFirst().minuteAt()).isEqualTo(MINUTE.minus(Duration.ofMinutes(14)));
        assertThat(recent.getLast().minuteAt()).isEqualTo(MINUTE);
    }

    @Test
    void rowsGoWithTheirSessionAndUser() {
        store.insertSamples(ENDED_RECENT, STUDENT, List.of(sample(MINUTE)));
        store.upsertState(ENDED_RECENT, STUDENT, FocusState.FOCUS, "v", MINUTE);
        store.insertSamples(SESSION, OTHER, List.of(sample(MINUTE)));

        jdbc.update("DELETE FROM session_participants WHERE session_id = ?", ENDED_RECENT);
        jdbc.update("DELETE FROM study_sessions WHERE id = ?", ENDED_RECENT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM focus_samples WHERE session_id = ?", Long.class,
                ENDED_RECENT)).isZero();
        assertThat(count("focus_states")).isZero();

        jdbc.update("DELETE FROM session_participants WHERE user_id = ?", OTHER);
        jdbc.update("DELETE FROM users WHERE id = ?", OTHER);
        assertThat(count("focus_samples")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM focus_consents WHERE user_id = ?", Long.class, OTHER))
                .isZero();
    }

    // ------------------------------------------------------------------ consent row lock vs withdrawal

    @Test
    void withdrawalWaitsForARunningWriteAndThenDeletesItsRows() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JdbcFocusStore pausing = new JdbcFocusStore(jdbc) {
            @Override
            public Optional<Consent> lockConsent(long userId) {
                Optional<Consent> consent = super.lockConsent(userId);
                locked.countDown();
                try {
                    if (!release.await(20, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test release missing");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return consent;
            }
        };
        FocusRecorder writer = recorder(pausing);

        Future<?> write = pool.submit(() -> tx.executeWithoutResult(s ->
                writer.saveSamples(STUDENT, SESSION, List.of(sample(MINUTE)))));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        Future<?> withdraw = pool.submit(() -> tx.executeWithoutResult(s -> store.deleteUserData(STUDENT)));

        Thread.sleep(700);
        assertThat(withdraw.isDone()).as("withdrawal waits for the FOR SHARE lock").isFalse();
        release.countDown();
        write.get(20, TimeUnit.SECONDS);
        withdraw.get(20, TimeUnit.SECONDS);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM focus_samples WHERE user_id = ?", Long.class, STUDENT))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM focus_consents WHERE user_id = ?", Long.class, STUDENT))
                .isZero();
    }

    @Test
    void withdrawalWaitsForARunningStateWriteToo() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JdbcFocusStore pausing = new JdbcFocusStore(jdbc) {
            @Override
            public Optional<Consent> lockConsent(long userId) {
                Optional<Consent> consent = super.lockConsent(userId);
                locked.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return consent;
            }
        };
        FocusRecorder writer = recorder(pausing);

        Future<FocusRecorder.SaveResult> write = pool.submit(() -> tx.execute(s ->
                writer.saveState(STUDENT, SESSION, FocusState.FOCUS, "v", MINUTE)));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        Future<?> withdraw = pool.submit(() -> tx.executeWithoutResult(s -> store.deleteUserData(STUDENT)));
        Thread.sleep(700);
        assertThat(withdraw.isDone()).isFalse();
        release.countDown();

        assertThat(write.get(20, TimeUnit.SECONDS)).isEqualTo(FocusRecorder.SaveResult.SAVED);
        withdraw.get(20, TimeUnit.SECONDS);
        assertThat(count("focus_states")).isZero();
    }

    @Test
    void writeAfterACommittedWithdrawalIs403AndStoresNothing() {
        tx.executeWithoutResult(s -> store.deleteUserData(STUDENT));
        FocusRecorder writer = recorder(store);

        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                writer.saveSamples(STUDENT, SESSION, List.of(sample(MINUTE)))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        FocusRecorder.SaveResult result =
                tx.execute(s -> writer.saveState(STUDENT, SESSION, FocusState.FOCUS, "v", MINUTE));
        assertThat(result).isEqualTo(FocusRecorder.SaveResult.NO_CONSENT);
        assertThat(count("focus_samples")).isZero();
        assertThat(count("focus_states")).isZero();
    }

    // ------------------------------------------------------------------ account withdrawal vs consent withdrawal

    /**
     * PM 2026-11-26 (PT-13): account withdrawal anonymizes the users row and keeps every focus row, also the rows of
     * other students in the classes the withdrawn teacher owns. It still runs together with consent withdrawals
     * (session rows first, then the users row) without a deadlock.
     */
    @Test
    void accountWithdrawalKeepsFocusRowsAndRunsWithConsentWithdrawalsWithoutDeadlock() throws Exception {
        AccountCleanup cleanup = new AccountCleanup(JdbcClient.create(jdbc));
        for (int round = 0; round < 5; round++) {
            if (round > 0) {
                seed();
            }
            for (long user : List.of(STUDENT, OTHER)) {
                store.insertSamples(SESSION, user, List.of(sample(MINUTE), sample(MINUTE.minusSeconds(60))));
                store.upsertState(SESSION, user, FocusState.FOCUS, "v", MINUTE);
            }
            CyclicBarrier start = new CyclicBarrier(3);
            List<Future<?>> runs = List.of(
                    pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        tx.executeWithoutResult(s -> {   // same SQL order as WithdrawalService (owner of SESSION)
                            cleanup.lockHelpSessions(TEACHER);
                            jdbc.update("UPDATE study_sessions SET ended_at = now() WHERE owner_id = ? AND ended_at IS NULL",
                                    TEACHER);
                            assertThat(cleanup.anonymize(TEACHER)).isEqualTo(1);
                        });
                        return null;
                    }),
                    pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        tx.executeWithoutResult(s -> store.deleteUserData(STUDENT));
                        return null;
                    }),
                    pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        tx.executeWithoutResult(s -> {   // the student withdraws the account too
                            cleanup.lockHelpSessions(OTHER);
                            assertThat(cleanup.anonymize(OTHER)).isEqualTo(1);
                        });
                        return null;
                    }));
            for (Future<?> run : runs) {
                run.get(30, TimeUnit.SECONDS);   // a deadlock would surface as an exception from PostgreSQL
            }
            // consent withdrawal deleted STUDENT's rows; account withdrawal deleted nothing
            assertThat(jdbc.queryForObject("SELECT count(*) FROM focus_samples WHERE user_id = ?", Long.class, OTHER))
                    .isEqualTo(2L);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM focus_states WHERE user_id = ?", Long.class, OTHER))
                    .isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM focus_consents WHERE user_id = ?", Long.class, OTHER))
                    .isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM focus_samples WHERE user_id = ?", Long.class, STUDENT))
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM study_sessions WHERE owner_id = ?", Long.class, TEACHER))
                    .isEqualTo(3L);
            assertThat(jdbc.queryForList("SELECT provider FROM users WHERE id IN (?,?)", String.class, TEACHER, OTHER))
                    .containsOnly("withdrawn");
            assertThat(cleanup.anonymize(TEACHER)).isZero();   // second withdrawal changes nothing
        }
    }

    // ------------------------------------------------------------------ account gate (Codex P1, PT-55)

    /** Runs body in a transaction on the pool, signals once body ran, and commits only after release. */
    private static Future<?> heldTransaction(Runnable body, CountDownLatch ran, CountDownLatch release) {
        return pool.submit(() -> tx.executeWithoutResult(s -> {
            body.run();
            ran.countDown();
            try {
                if (!release.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test release missing");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }));
    }

    @Test
    void creatingPathWaitsForARunningWithdrawalAndThenSeesTheAccountWithdrawn() throws Exception {
        AccountLocks locks = new AccountLocks(JdbcClient.create(jdbc));
        AccountCleanup cleanup = new AccountCleanup(JdbcClient.create(jdbc));
        CountDownLatch ran = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> withdrawal = heldTransaction(() -> {
            assertThat(locks.lockForWithdrawal(STUDENT)).isTrue();
            cleanup.lockHelpSessions(STUDENT);
            assertThat(cleanup.anonymize(STUDENT)).isEqualTo(1);
        }, ran, release);
        assertThat(ran.await(10, TimeUnit.SECONDS)).isTrue();

        Future<Boolean> create = pool.submit(() -> tx.execute(s -> locks.lockActive(STUDENT)));
        Thread.sleep(700);
        assertThat(create.isDone()).as("create / join / help request waits for the withdrawal").isFalse();
        release.countDown();
        withdrawal.get(20, TimeUnit.SECONDS);

        assertThat(create.get(20, TimeUnit.SECONDS)).as("then sees provider='withdrawn' and is refused").isFalse();
    }

    @Test
    void withdrawalWaitsForARunningCreateAndThenFindsItsClassToClose() throws Exception {
        AccountLocks locks = new AccountLocks(JdbcClient.create(jdbc));
        AccountCleanup cleanup = new AccountCleanup(JdbcClient.create(jdbc));
        long created = 90009L;
        CountDownLatch ran = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> create = heldTransaction(() -> {
            assertThat(locks.lockActive(TEACHER)).isTrue();
            jdbc.update("INSERT INTO study_sessions(id,created_at,updated_at,title,topic,invite_code,owner_id)"
                    + " VALUES (?,now(),now(),'gate','java','F90009',?)", created, TEACHER);
        }, ran, release);
        assertThat(ran.await(10, TimeUnit.SECONDS)).isTrue();

        Future<List<Long>> withdrawal = pool.submit(() -> tx.execute(s -> {
            assertThat(locks.lockForWithdrawal(TEACHER)).isTrue();
            cleanup.lockHelpSessions(TEACHER);
            List<Long> open = cleanup.openOwnedSessionIds(TEACHER);
            assertThat(cleanup.anonymize(TEACHER)).isEqualTo(1);
            return open;
        }));
        Thread.sleep(700);
        assertThat(withdrawal.isDone()).as("withdrawal waits for the running create").isFalse();
        release.countDown();
        create.get(20, TimeUnit.SECONDS);

        assertThat(withdrawal.get(20, TimeUnit.SECONDS)).contains(SESSION, created);
    }

    @Test
    void withdrawalGateDoesNotBlockForeignKeyInserts() throws Exception {
        AccountLocks locks = new AccountLocks(JdbcClient.create(jdbc));
        CountDownLatch ran = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> gate = heldTransaction(() -> assertThat(locks.lockForWithdrawal(STUDENT)).isTrue(), ran, release);
        assertThat(ran.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            // FK check takes FOR KEY SHARE on users, which FOR NO KEY UPDATE allows: no new wait on class-row-first paths
            pool.submit(() -> tx.executeWithoutResult(s -> store.insertSamples(SESSION, STUDENT, List.of(sample(MINUTE)))))
                    .get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            gate.get(20, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM focus_samples WHERE user_id = ?", Long.class, STUDENT))
                .isEqualTo(1L);
    }

    // ------------------------------------------------------------------ retention

    @Test
    void retentionDeletesOnlyEndedLongAgoAndOlderThanTheAgeBound() {
        Instant now = Instant.now();
        store.insertSamples(ENDED_OLD, STUDENT, List.of(sample(now.minus(Duration.ofDays(32)))));
        store.upsertState(ENDED_OLD, STUDENT, FocusState.FOCUS, "v", now.minus(Duration.ofDays(31)));
        store.insertSamples(ENDED_RECENT, STUDENT, List.of(sample(now.minus(Duration.ofDays(30)))));
        store.upsertState(ENDED_RECENT, STUDENT, FocusState.FOCUS, "v", now.minus(Duration.ofDays(29)));
        Instant old = now.minus(Duration.ofDays(40)).truncatedTo(ChronoUnit.MINUTES);
        Instant young = now.minus(Duration.ofDays(30)).truncatedTo(ChronoUnit.MINUTES);
        store.insertSamples(SESSION, STUDENT, List.of(sample(old), sample(young)));
        store.upsertState(SESSION, STUDENT, FocusState.FOCUS, "v", now.minus(Duration.ofDays(35)));
        store.upsertState(SESSION, OTHER, FocusState.FOCUS, "v", now.minus(Duration.ofMinutes(1)));
        FocusRetention retention = new FocusRetention(store, FocusProperties.defaults(), Clock.fixed(now, ZoneOffset.UTC));

        assertThat(retention.purgeExpired()).isEqualTo(2);   // ENDED_OLD sample + state
        assertThat(retention.purgeStale()).isEqualTo(2);     // SESSION 40-day sample + 35-day state

        assertThat(jdbc.queryForObject("SELECT count(*) FROM focus_samples WHERE session_id = ?", Long.class,
                ENDED_OLD)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM focus_samples WHERE session_id = ?", Long.class,
                ENDED_RECENT)).isEqualTo(1L);
        assertThat(store.recentSamples(SESSION, STUDENT, old)).extracting(FocusStore.Sample::minuteAt)
                .containsExactly(young);
        assertThat(store.state(SESSION, STUDENT)).isEmpty();
        assertThat(store.state(SESSION, OTHER)).isPresent();
        assertThat(store.state(ENDED_RECENT, STUDENT)).isPresent();
    }
}
