package com.my.tutipal_back.learning.conversation.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import com.my.tutipal_back.learning.conversation.ConversationProperties;
import com.my.tutipal_back.learning.conversation.service.ConversationRetention;

/**
 * PT-106 / PT-13 retention against a disposable PostgreSQL: dedicated Gradle task conversationDbTest only (excluded
 * from test). Loopback tutipal_conversation_e2e database, synthetic data, Flyway clean first; missing env fails
 * instead of skipping. Checks the V24 backfill of existing sessions (migrated to V23, seeded, then V24), the 0..90
 * CHECK, class retention (ended_at + ai_retention_days, NULL = 90, open sessions never, saved kept, session-scoped
 * memories removed), personal retention (30 days, saved kept), CASCADE to messages / summaries, the save toggle
 * and SKIP LOCKED.
 */
class ConversationRetentionPostgresTest {

    private static final long TEACHER = 9101L;
    private static final long STUDENT = 9102L;
    private static final long LEGACY_SESSION = 91000L;
    private static final long OPEN = 91001L;
    private static final long EXPIRED = 91002L;
    private static final long RECENT = 91003L;
    private static final long ZERO = 91004L;
    private static final long NULL_DAYS = 91005L;
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static ExecutorService pool;
    private JdbcConversationStore store;
    private ConversationRetention retention;

    @BeforeAll
    static void prepareDisposableDatabase() {
        String url = System.getenv("CONVERSATION_TEST_DB_URL");
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/tutipal_conversation_e2e")
                || !"disposable".equals(System.getenv("CONVERSATION_TEST_DB_ALLOW_RESET"))) {
            throw new IllegalStateException("Provide an explicit loopback tutipal_conversation_e2e disposable database");
        }
        String password = System.getenv("CONVERSATION_TEST_DB_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("Missing disposable DB password");
        }
        dataSource = new DriverManagerDataSource(url, "tutipal_conversation_e2e", password);
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        // schema before V24, with an existing session and class conversation
        Flyway.configure().dataSource(dataSource).target("23").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        user(TEACHER);
        user(STUDENT);
        jdbc.update("INSERT INTO study_sessions(id,created_at,updated_at,title,topic,invite_code,owner_id,ended_at)"
                + " VALUES (?,now(),now(),'legacy','java','CV0000',?,?)", LEGACY_SESSION, TEACHER,
                NOW.minus(Duration.ofDays(400)).atOffset(ZoneOffset.UTC));
        jdbc.update("INSERT INTO ai_conversations(user_id,session_id,topic) VALUES (?,?,'java')", STUDENT,
                LEGACY_SESSION);

        Flyway.configure().dataSource(dataSource).load().migrate();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version = '24' AND success",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT ai_retention_days FROM study_sessions WHERE id = ?", Integer.class,
                LEGACY_SESSION)).isEqualTo(90);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_conversations WHERE session_id = ? AND saved_at IS NULL",
                Long.class, LEGACY_SESSION)).isEqualTo(1L);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterAll
    static void stopPool() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private static void user(long id) {
        jdbc.update("INSERT INTO users(id,created_at,updated_at,name,provider,provider_id,role)"
                + " VALUES (?,now(),now(),'synthetic','dev',?,'USER') ON CONFLICT DO NOTHING", id, "conv-" + id);
    }

    private static void session(long id, Instant endedAt, Integer days) {
        jdbc.update("INSERT INTO study_sessions(id,created_at,updated_at,title,topic,invite_code,owner_id,ended_at,"
                + "ai_retention_days) VALUES (?,now(),now(),'retention e2e','java',?,?,?,?)", id, "C" + (id % 100000),
                TEACHER, endedAt == null ? null : endedAt.atOffset(ZoneOffset.UTC), days);
    }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM ai_memories");
        jdbc.update("DELETE FROM ai_conversations");
        jdbc.update("DELETE FROM study_sessions");
        user(TEACHER);
        user(STUDENT);
        session(OPEN, null, 0);
        session(EXPIRED, daysAgo(8), 7);
        session(RECENT, daysAgo(6), 7);
        session(ZERO, NOW.minusSeconds(60), 0);
        session(NULL_DAYS, daysAgo(89), null);
        store = new JdbcConversationStore(jdbc);
        retention = new ConversationRetention(store, ConversationProperties.defaults(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Instant daysAgo(long days) {
        return NOW.minus(Duration.ofDays(days));
    }

    /** A conversation with one saved pair and a summary, last activity at {@code lastActivity}. */
    private long conversation(Long sessionId, Instant lastActivity) {
        long id = store.insertConversation(STUDENT, sessionId, "java", lastActivity).id();
        ConversationStore.SavedPair pair = store.insertPair(id, "질문", "답변", "질문", lastActivity);
        store.insertSummary(id, "요약", pair.user().id(), pair.assistant().id(), lastActivity);
        return id;
    }

    private static boolean exists(long conversationId) {
        return count("SELECT count(*) FROM ai_conversations WHERE id = ?", conversationId) == 1;
    }

    private static long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    @Test
    void retentionColumnRejectsValuesOutsideZeroToNinety() {
        assertThatThrownBy(() -> jdbc.update("UPDATE study_sessions SET ai_retention_days = 91 WHERE id = ?", OPEN))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE study_sessions SET ai_retention_days = -1 WHERE id = ?", OPEN))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE study_sessions SET ai_retention_days = 90 WHERE id = ?", OPEN);
        jdbc.update("UPDATE study_sessions SET ai_retention_days = 0 WHERE id = ?", OPEN);
    }

    @Test
    void classRetentionFollowsEachSessionAndKeepsSavedConversations() {
        long expired = conversation(EXPIRED, daysAgo(9));
        long expiredDeleted = conversation(EXPIRED, daysAgo(9));
        store.softDeleteConversation(expiredDeleted, daysAgo(1));
        long saved = conversation(EXPIRED, daysAgo(9));
        store.setSaved(saved, daysAgo(8));
        store.insertMemory(STUDENT, EXPIRED, saved, "저장한 대화의 수업 기억", daysAgo(9));
        store.insertMemory(STUDENT, EXPIRED, expired, "같이 지워질 기억", daysAgo(9));
        long recent = conversation(RECENT, daysAgo(400));
        long open = conversation(OPEN, daysAgo(400));
        store.insertMemory(STUDENT, OPEN, open, "진행 중 수업 기억", daysAgo(400));
        long zero = conversation(ZERO, NOW.minusSeconds(120));
        long nullDays = conversation(NULL_DAYS, daysAgo(100));
        long personal = conversation(null, NOW);
        store.insertMemory(STUDENT, null, personal, "개인 기억", NOW);

        int deleted = retention.purgeExpiredClass();

        assertThat(deleted).as("expired, expiredDeleted, zero + the saved conversation's memory").isEqualTo(4);
        assertThat(exists(expired)).isFalse();
        assertThat(exists(expiredDeleted)).isFalse();
        assertThat(exists(zero)).isFalse();
        assertThat(count("SELECT count(*) FROM ai_conversation_messages WHERE conversation_id IN (?,?,?)",
                expired, expiredDeleted, zero)).isZero();
        assertThat(count("SELECT count(*) FROM ai_conversation_summaries WHERE conversation_id IN (?,?,?)",
                expired, expiredDeleted, zero)).isZero();
        assertThat(exists(saved)).isTrue();
        assertThat(count("SELECT count(*) FROM ai_conversation_messages WHERE conversation_id = ?", saved))
                .isEqualTo(2);
        assertThat(count("SELECT count(*) FROM ai_memories WHERE session_id = ?", EXPIRED)).isZero();
        assertThat(exists(recent)).isTrue();
        assertThat(exists(open)).isTrue();
        assertThat(exists(nullDays)).as("NULL counts as 90 days").isTrue();
        assertThat(exists(personal)).isTrue();
        assertThat(count("SELECT count(*) FROM ai_memories WHERE session_id = ? OR session_id IS NULL", OPEN))
                .isEqualTo(2);

        assertThat(retention.purgeExpiredClass()).isZero();
    }

    @Test
    void personalRetentionRemovesUnsavedConversationsIdleMoreThan30Days() {
        long old = conversation(null, daysAgo(31));
        store.insertMemory(STUDENT, null, old, "옛 기억", daysAgo(31));
        long oldDeleted = conversation(null, daysAgo(31));
        store.softDeleteConversation(oldDeleted, daysAgo(2));
        long recent = conversation(null, daysAgo(29));
        long savedOld = conversation(null, daysAgo(300));
        store.setSaved(savedOld, daysAgo(299));
        long emptyOld = store.insertConversation(STUDENT, null, "java", daysAgo(31)).id();
        long classOld = conversation(OPEN, daysAgo(300));

        int deleted = retention.purgeIdlePersonal();

        assertThat(deleted).isEqualTo(3);
        assertThat(exists(old)).isFalse();
        assertThat(exists(oldDeleted)).isFalse();
        assertThat(exists(emptyOld)).isFalse();
        assertThat(count("SELECT count(*) FROM ai_memories WHERE conversation_id = ?", old)).isZero();
        assertThat(count("SELECT count(*) FROM ai_conversation_messages WHERE conversation_id = ?", old)).isZero();
        assertThat(exists(recent)).isTrue();
        assertThat(exists(savedOld)).isTrue();
        assertThat(exists(classOld)).isTrue();
    }

    @Test
    void saveToggleKeepsTheFirstTimeAndIgnoresDeletedConversations() {
        long c = conversation(null, NOW);

        assertThat(store.setSaved(c, daysAgo(2)).orElseThrow().savedAt()).isEqualTo(daysAgo(2));
        assertThat(store.setSaved(c, NOW).orElseThrow().savedAt()).isEqualTo(daysAgo(2));
        assertThat(store.findOwned(STUDENT, c).orElseThrow().saved()).isTrue();
        assertThat(store.setSaved(c, null).orElseThrow().savedAt()).isNull();

        store.softDeleteConversation(c, NOW);
        assertThat(store.setSaved(c, NOW)).isEmpty();
        assertThat(count("SELECT count(*) FROM ai_conversations WHERE id = ? AND saved_at IS NULL", c)).isEqualTo(1);
    }

    /** A row locked by a turn or a save toggle is skipped, not waited for, and stays. */
    @Test
    void retentionSkipsLockedRows() throws Exception {
        long locked = conversation(null, daysAgo(40));
        long free = conversation(null, daysAgo(40));
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
            store.lock(locked);
            held.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            assertThat(retention.purgeIdlePersonal()).isEqualTo(1);
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
        assertThat(exists(locked)).isTrue();
        assertThat(exists(free)).isFalse();
        assertThat(retention.purgeIdlePersonal()).isEqualTo(1);
        assertThat(List.of(exists(locked), exists(free))).containsOnly(false);
    }
}
