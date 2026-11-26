package com.my.tutipal_back.learning.insights.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import com.my.tutipal_back.learning.insights.dto.ConceptView;
import com.my.tutipal_back.learning.insights.dto.QuizConceptView;
import com.my.tutipal_back.learning.quiz.entity.QuizType;
import com.my.tutipal_back.users.repository.AccountCleanup;

/**
 * PT-87 AC-1, AC-2, AC-6 (INSERT condition), AC-7 and AC-10/14 (store side) against a real disposable PostgreSQL.
 * Dedicated task only (insightsDbTest, same pattern as ReviewStorePostgresTest / reviewDbTest): excluded from the
 * default test task; missing env fails instead of skipping. Synthetic rows only.
 *
 * <p>Env: INSIGHTS_TEST_DB_URL=jdbc:postgresql://127.0.0.1:PORT/tutipal_insights_e2e,
 * INSIGHTS_TEST_DB_ALLOW_RESET=disposable, INSIGHTS_TEST_DB_PASSWORD (user tutipal_insights_e2e).
 */
class ConceptInsightsPostgresTest {

    static final Instant NOW = Instant.parse("2026-11-25T03:00:00Z");
    static JdbcTemplate jdbc;
    static DriverManagerDataSource dataSource;

    static final long TEACHER = 101;
    static final long OTHER_TEACHER = 102;
    static final long KIM = 201;
    static final long LEE = 202;
    static final long PARK = 203;
    static final long OUTSIDER = 299;
    static final long ACTIVE = 11;
    static final long OTHER_SESSION = 12;
    static final long ENDED = 13;
    static final long CHOICE_QUIZ = 1001;
    static final long TEXT_QUIZ = 1002;
    static final long UNTAGGED_QUIZ = 1003;

    ChatQuestionStore questions;
    ConceptStore concepts;
    MasteryStore mastery;

    @BeforeAll
    static void prepareDisposableDatabase() {
        String url = System.getenv("INSIGHTS_TEST_DB_URL");
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/tutipal_insights_e2e")
                || !"disposable".equals(System.getenv("INSIGHTS_TEST_DB_ALLOW_RESET"))) {
            throw new IllegalStateException("Provide an explicit loopback tutipal_insights_e2e disposable database");
        }
        String password = System.getenv("INSIGHTS_TEST_DB_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("Missing disposable DB password");
        }
        dataSource = new DriverManagerDataSource(url, "tutipal_insights_e2e", password);
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        // Schema as it was before PT-87 (V19 is the last migration that exists on every branch).
        Flyway.configure().dataSource(dataSource).target("19").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        seedExistingRows();
        Map<String, Long> before = counts();

        Flyway.configure().dataSource(dataSource).load().migrate();

        // AC-1: existing rows are preserved and nothing is backfilled
        assertThat(counts()).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM session_concepts")).isZero();
        assertThat(count("SELECT count(*) FROM quiz_concepts")).isZero();
        assertThat(count("SELECT count(*) FROM chat_questions")).isZero();
    }

    static long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    static Map<String, Long> counts() {
        return Map.of(
                "users", count("SELECT count(*) FROM users"),
                "study_sessions", count("SELECT count(*) FROM study_sessions"),
                "session_participants", count("SELECT count(*) FROM session_participants"),
                "quizzes", count("SELECT count(*) FROM quizzes"),
                "quiz_answers", count("SELECT count(*) FROM quiz_answers"));
    }

    static Timestamp at(Instant instant) {
        return Timestamp.from(instant);
    }

    static void user(long id, String name) {
        jdbc.update("INSERT INTO users(id,created_at,updated_at,name,provider,provider_id,role) "
                + "VALUES (?,now(),now(),?,'dev',?,'USER') ON CONFLICT DO NOTHING", id, name, "insights-" + id);
    }

    static void session(long id, long owner, String code, boolean ended) {
        jdbc.update("INSERT INTO study_sessions(id,created_at,updated_at,title,topic,invite_code,owner_id,ended_at) "
                + "VALUES (?,now(),now(),'합성 수업','코딩',?,?,?) ON CONFLICT DO NOTHING",
                id, code, owner, ended ? at(NOW) : null);
    }

    static void participant(long session, long user, String role) {
        jdbc.update("INSERT INTO session_participants(created_at,updated_at,session_id,user_id,role) "
                + "VALUES (now(),now(),?,?,?) ON CONFLICT DO NOTHING", session, user, role);
    }

    static void quiz(long id, long session, String type) {
        jdbc.update("INSERT INTO quizzes(id,created_at,updated_at,session_id,type,question,choices,answer_index,"
                + "closes_at) VALUES (?,now(),now(),?,?,'합성 문항',"
                + ("CHOICE".equals(type) ? "ARRAY['가','나','다','라']" : "NULL") + ","
                + ("CHOICE".equals(type) ? "0" : "NULL") + ",now()) ON CONFLICT DO NOTHING", id, session, type);
    }

    static void answer(long id, long quiz, long user, Boolean correct, Instant createdAt) {
        jdbc.update("INSERT INTO quiz_answers(id,created_at,updated_at,quiz_id,user_id,choice_index,correct,graded_by) "
                + "VALUES (?,?,?,?,?,0,?,'AUTO') ON CONFLICT DO NOTHING",
                id, at(createdAt), at(createdAt), quiz, user, correct);
    }

    static void seedExistingRows() {
        user(TEACHER, "선생님");
        user(OTHER_TEACHER, "다른 선생님");
        user(KIM, "김학생");
        user(LEE, "이학생");
        user(PARK, "박학생");
        user(OUTSIDER, "외부인");
        session(ACTIVE, TEACHER, "INS011", false);
        session(OTHER_SESSION, OTHER_TEACHER, "INS012", false);
        session(ENDED, TEACHER, "INS013", true);
        participant(ACTIVE, TEACHER, "TEACHER");
        participant(ACTIVE, KIM, "STUDENT");
        participant(ACTIVE, LEE, "STUDENT");
        participant(ACTIVE, PARK, "STUDENT");
        participant(OTHER_SESSION, OTHER_TEACHER, "TEACHER");
        participant(OTHER_SESSION, KIM, "STUDENT");
        participant(ENDED, TEACHER, "TEACHER");
        participant(ENDED, KIM, "STUDENT");
        quiz(CHOICE_QUIZ, ACTIVE, "CHOICE");
        quiz(TEXT_QUIZ, ACTIVE, "TEXT");
        quiz(UNTAGGED_QUIZ, ACTIVE, "CHOICE");
        quiz(1201, OTHER_SESSION, "CHOICE");
        answer(5001, CHOICE_QUIZ, KIM, true, NOW.minusSeconds(300));
        answer(5002, CHOICE_QUIZ, LEE, false, NOW.minusSeconds(290));
        answer(5003, TEXT_QUIZ, KIM, true, NOW.minusSeconds(200));
        answer(5004, TEXT_QUIZ, LEE, null, NOW.minusSeconds(190));
        answer(5005, UNTAGGED_QUIZ, KIM, false, NOW.minusSeconds(100));
        answer(5006, CHOICE_QUIZ, PARK, true, NOW.minusSeconds(280));
        answer(5007, 1201, KIM, true, NOW.minusSeconds(50));
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM chat_questions");
        jdbc.update("DELETE FROM quiz_concepts");
        jdbc.update("DELETE FROM session_concepts");
        participant(ACTIVE, PARK, "STUDENT");
        questions = new ChatQuestionStore(jdbc);
        concepts = new ConceptStore(jdbc);
        mastery = new MasteryStore(jdbc);
    }

    long insertQuestion(long session, long user, String text, Instant createdAt) {
        Long id = jdbc.queryForObject("INSERT INTO chat_questions(session_id,user_id,question_text,created_at) "
                + "VALUES (?,?,?,?) RETURNING id", Long.class, session, user, text, at(createdAt));
        return id == null ? -1 : id;
    }

    long questionCount(long session, long user) {
        return count("SELECT count(*) FROM chat_questions WHERE session_id = ? AND user_id = ?", session, user);
    }

    // ------------------------------------------------------------------ AC-1 constraints from V22

    @Test
    void questionTextMustBeOneToOneThousandCharacters() {
        assertThatThrownBy(() -> insertQuestion(ACTIVE, KIM, "", NOW)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertQuestion(ACTIVE, KIM, "가".repeat(1001), NOW))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertQuestion(ACTIVE, KIM, "가".repeat(1000), NOW);
        assertThat(questionCount(ACTIVE, KIM)).isEqualTo(1);
    }

    @Test
    void conceptNameMustBeOneToThirtyCharactersAfterTrimAndUniquePerSessionByNormalizedName() {
        String insert = "INSERT INTO session_concepts(session_id,name,normalized_name,created_at) VALUES (?,?,?,now())";
        assertThatThrownBy(() -> jdbc.update(insert, ACTIVE, "   ", "x"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, ACTIVE, "가".repeat(31), "x"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, ACTIVE, "반복문", "반복문");
        assertThatThrownBy(() -> jdbc.update(insert, ACTIVE, "반복문 ", "반복문"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, OTHER_SESSION, "반복문", "반복문");   // same name in another session is fine
    }

    @Test
    void quizConceptSourceIsTeacherOrAi() {
        Long conceptId = jdbc.queryForObject("INSERT INTO session_concepts(session_id,name,normalized_name,created_at) "
                + "VALUES (?,'변수','변수',now()) RETURNING id", Long.class, ACTIVE);
        String link = "INSERT INTO quiz_concepts(quiz_id,concept_id,source,created_at) VALUES (?,?,?,now())";
        jdbc.update(link, CHOICE_QUIZ, conceptId, "TEACHER");
        jdbc.update(link, TEXT_QUIZ, conceptId, "AI");
        assertThatThrownBy(() -> jdbc.update(link, UNTAGGED_QUIZ, conceptId, "ROBOT"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(link, CHOICE_QUIZ, conceptId, "AI"))
                .isInstanceOf(DataIntegrityViolationException.class);   // primary key (quiz_id, concept_id)
    }

    // ------------------------------------------------------------------ AC-6 INSERT condition

    @Test
    void questionIsStoredOnlyForStudentParticipantsOfOpenSessions() {
        assertThat(questions.insertIfActiveStudent(ACTIVE, KIM, "변수가 뭐예요? 합성 질문")).isEqualTo(1);
        assertThat(questions.insertIfActiveStudent(ACTIVE, TEACHER, "선생님 미리보기 질문")).isZero();
        assertThat(questions.insertIfActiveStudent(ACTIVE, OUTSIDER, "참가하지 않은 사람 질문")).isZero();
        assertThat(questions.insertIfActiveStudent(ENDED, KIM, "끝난 수업 질문입니다")).isZero();

        assertThat(count("SELECT count(*) FROM chat_questions")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT question_text FROM chat_questions WHERE session_id = ? AND user_id = ?",
                String.class, ACTIVE, KIM)).isEqualTo("변수가 뭐예요? 합성 질문");
    }

    @Test
    void recentQuestionsReadsOneSessionNewestFirstWithinTheLimit() {
        for (int i = 1; i <= 5; i++) {
            insertQuestion(ACTIVE, i % 2 == 0 ? KIM : LEE, "합성 질문 번호 " + i, NOW.minusSeconds(100 - i));
        }
        insertQuestion(OTHER_SESSION, KIM, "다른 수업의 합성 질문", NOW);

        List<QuestionRow> rows = questions.recentQuestions(ACTIVE, 3);

        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(r -> r.text().revealForEmbedding())
                .containsExactlyInAnyOrder("합성 질문 번호 5", "합성 질문 번호 4", "합성 질문 번호 3");
        assertThat(rows).extracting(QuestionRow::userId).containsOnly(KIM, LEE);
    }

    // ------------------------------------------------------------------ AC-7 deletion

    @Test
    void retentionDeletesOnlyExpiredRowsInBatches() {
        Instant cutoff = NOW.minus(Duration.ofDays(30));
        insertQuestion(ACTIVE, KIM, "오래된 합성 질문 1", cutoff.minusSeconds(3));
        insertQuestion(ACTIVE, KIM, "오래된 합성 질문 2", cutoff.minusSeconds(2));
        insertQuestion(ACTIVE, LEE, "오래된 합성 질문 3", cutoff.minusSeconds(1));
        insertQuestion(ACTIVE, LEE, "최근 합성 질문입니다", NOW.minus(Duration.ofDays(1)));

        assertThat(questions.deleteCreatedBefore(cutoff, 2)).isEqualTo(2);
        assertThat(questions.deleteCreatedBefore(cutoff, 2)).isEqualTo(1);
        assertThat(questions.deleteCreatedBefore(cutoff, 2)).isZero();

        assertThat(jdbc.queryForList("SELECT question_text FROM chat_questions", String.class))
                .containsExactly("최근 합성 질문입니다");
    }

    @Test
    void leavingDeletesOnlyThatStudentInThatSession() {
        insertQuestion(ACTIVE, KIM, "김학생 합성 질문", NOW);
        insertQuestion(ACTIVE, LEE, "이학생 합성 질문", NOW);
        insertQuestion(OTHER_SESSION, KIM, "다른 수업 김학생 질문", NOW);

        questions.deleteBySessionAndUser(ACTIVE, KIM);

        assertThat(questionCount(ACTIVE, KIM)).isZero();
        assertThat(questionCount(ACTIVE, LEE)).isEqualTo(1);
        assertThat(questionCount(OTHER_SESSION, KIM)).isEqualTo(1);
    }

    @Test
    void accountWithdrawalKeepsQuestionsAndConceptsAndAnonymizesTheAccount() {
        user(401, "탈퇴 선생님");
        user(402, "탈퇴 학생");
        session(41, 401, "INS041", false);
        participant(41, 401, "TEACHER");
        participant(41, 402, "STUDENT");
        participant(41, KIM, "STUDENT");
        participant(ACTIVE, 402, "STUDENT");
        insertQuestion(41, 402, "탈퇴 학생의 수업41 질문", NOW);
        insertQuestion(41, KIM, "김학생의 수업41 질문", NOW);
        insertQuestion(ACTIVE, 402, "탈퇴 학생의 수업11 질문", NOW);
        insertQuestion(ACTIVE, KIM, "김학생의 수업11 질문", NOW);
        jdbc.update("INSERT INTO session_concepts(session_id,name,normalized_name,created_at) "
                + "VALUES (41,'수업41 개념','수업41 개념',now())");
        AccountCleanup cleanup = new AccountCleanup(JdbcClient.create(dataSource));

        // PM 2026-11-26 (PT-13): withdrawal anonymizes the account and keeps every record
        assertThat(cleanup.anonymize(401L)).isEqualTo(1);

        assertThat(count("SELECT count(*) FROM chat_questions WHERE session_id = 41")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM session_concepts WHERE session_id = 41")).isEqualTo(1);
        assertThat(questionCount(ACTIVE, 402)).isEqualTo(1);

        assertThat(cleanup.anonymize(402L)).isEqualTo(1);

        assertThat(questionCount(ACTIVE, 402)).isEqualTo(1);
        assertThat(questionCount(ACTIVE, KIM)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT provider || '/' || provider_id || '/' || name || '/' || role || '/' "
                + "|| (email IS NULL) FROM users WHERE id IN (401, 402) ORDER BY id", String.class))
                .containsExactly("withdrawn/401/탈퇴한 사용자/USER/true", "withdrawn/402/탈퇴한 사용자/USER/true");
        assertThat(cleanup.anonymize(402L)).isZero();
        // the same social account signs up again as a new row (uk_users_provider is free again)
        jdbc.update("INSERT INTO users(id,created_at,updated_at,name,provider,provider_id,role) "
                + "VALUES (403,now(),now(),'다시 가입','dev','insights-402','USER')");
        assertThat(count("SELECT count(*) FROM users WHERE provider = 'dev' AND provider_id = 'insights-402'")).isEqualTo(1);
    }

    @Test
    void deletingASessionCascadesToItsQuestionsAndConcepts() {
        session(51, TEACHER, "INS051", false);
        insertQuestion(51, KIM, "지울 수업의 합성 질문", NOW);
        jdbc.update("INSERT INTO session_concepts(session_id,name,normalized_name,created_at) "
                + "VALUES (51,'지울 개념','지울 개념',now())");

        jdbc.update("DELETE FROM study_sessions WHERE id = 51");

        assertThat(count("SELECT count(*) FROM chat_questions WHERE session_id = 51")).isZero();
        assertThat(count("SELECT count(*) FROM session_concepts WHERE session_id = 51")).isZero();
    }

    // ------------------------------------------------------------------ AC-2 / AC-3 store side

    @Test
    void replaceIsAFullReplacementAndIdempotent() {
        concepts.replaceQuizConcepts(ACTIVE, CHOICE_QUIZ, List.of("변수", "반복문"), "TEACHER");
        concepts.replaceQuizConcepts(ACTIVE, CHOICE_QUIZ, List.of("변수", "반복문"), "TEACHER");

        Map<Long, List<QuizConceptView>> tags = concepts.quizConcepts(ACTIVE);
        assertThat(tags.get(CHOICE_QUIZ)).extracting(QuizConceptView::name).containsExactlyInAnyOrder("변수", "반복문");
        assertThat(concepts.sessionConcepts(ACTIVE)).hasSize(2);

        concepts.replaceQuizConcepts(ACTIVE, CHOICE_QUIZ, List.of("함수"), "AI");

        List<QuizConceptView> replaced = concepts.quizConcepts(ACTIVE).get(CHOICE_QUIZ);
        assertThat(replaced).extracting(QuizConceptView::name).containsExactly("함수");
        assertThat(replaced).extracting(QuizConceptView::source).containsExactly("AI");
    }

    @Test
    void caseVariantsShareOneConceptAcrossQuizzes() {
        concepts.replaceQuizConcepts(ACTIVE, CHOICE_QUIZ, List.of("FOR LOOP"), "TEACHER");
        concepts.replaceQuizConcepts(ACTIVE, TEXT_QUIZ, List.of("for loop"), "TEACHER");

        List<ConceptView> all = concepts.sessionConcepts(ACTIVE);
        assertThat(all).hasSize(1);
        Map<Long, List<QuizConceptView>> tags = concepts.quizConcepts(ACTIVE);
        assertThat(tags.get(CHOICE_QUIZ).getFirst().conceptId()).isEqualTo(tags.get(TEXT_QUIZ).getFirst().conceptId());
    }

    @Test
    void conceptsAreScopedToTheirSession() {
        concepts.replaceQuizConcepts(ACTIVE, CHOICE_QUIZ, List.of("변수"), "TEACHER");
        concepts.replaceQuizConcepts(OTHER_SESSION, 1201, List.of("변수"), "TEACHER");

        assertThat(concepts.sessionConcepts(ACTIVE)).hasSize(1);
        assertThat(concepts.sessionConcepts(OTHER_SESSION)).hasSize(1);
        assertThat(concepts.sessionConcepts(ACTIVE).getFirst().id())
                .isNotEqualTo(concepts.sessionConcepts(OTHER_SESSION).getFirst().id());
        assertThat(concepts.quizConcepts(ACTIVE)).doesNotContainKey(1201L);
    }

    // ------------------------------------------------------------------ AC-10 / AC-13 / AC-14 mastery store

    @Test
    void masterySnapshotHasTaggedObservationsInAnswerOrderAndCurrentStudentsOnly() {
        concepts.replaceQuizConcepts(ACTIVE, CHOICE_QUIZ, List.of("변수", "반복문"), "TEACHER");   // multi tag
        concepts.replaceQuizConcepts(ACTIVE, TEXT_QUIZ, List.of("변수"), "TEACHER");
        Map<String, Long> idByName = concepts.sessionConcepts(ACTIVE).stream()
                .collect(Collectors.toMap(ConceptView::name, ConceptView::id));
        long vars = idByName.get("변수");
        long loops = idByName.get("반복문");

        MasteryStore.Snapshot snapshot = mastery.load(ACTIVE);

        assertThat(snapshot.students()).extracting(MasteryStore.StudentInfo::userId)
                .containsExactlyInAnyOrder(KIM, LEE, PARK);
        assertThat(snapshot.concepts()).extracting(MasteryStore.ConceptInfo::name)
                .containsExactlyInAnyOrder("변수", "반복문");
        assertThat(snapshot.concepts()).filteredOn(c -> c.id() == vars)
                .extracting(MasteryStore.ConceptInfo::quizCount).containsExactly(2);
        assertThat(snapshot.concepts()).filteredOn(c -> c.id() == loops)
                .extracting(MasteryStore.ConceptInfo::quizCount).containsExactly(1);

        List<MasteryStore.Observation> kim = snapshot.observations().stream()
                .filter(o -> o.userId() == KIM && o.correct() != null).toList();
        // untagged quiz 1003 and the other session's quiz 1201 give nothing
        assertThat(kim).hasSize(3);
        assertThat(kim.stream().filter(o -> o.conceptId() == vars).map(MasteryStore.Observation::type).toList())
                .containsExactly(QuizType.CHOICE, QuizType.TEXT);   // created_at order
        assertThat(kim).filteredOn(o -> o.type() == QuizType.CHOICE)
                .extracting(MasteryStore.Observation::choiceCount).containsOnly(4);
        assertThat(kim).filteredOn(o -> o.conceptId() == loops).hasSize(1);
    }

    @Test
    void studentWhoLeftIsNotInTheSnapshot() {
        concepts.replaceQuizConcepts(ACTIVE, CHOICE_QUIZ, List.of("변수"), "TEACHER");
        jdbc.update("DELETE FROM session_participants WHERE session_id = ? AND user_id = ?", ACTIVE, PARK);

        MasteryStore.Snapshot snapshot = mastery.load(ACTIVE);

        Set<Long> students = snapshot.students().stream().map(MasteryStore.StudentInfo::userId)
                .collect(Collectors.toSet());
        assertThat(students).containsExactlyInAnyOrder(KIM, LEE);
        assertThat(students).doesNotContain(TEACHER);
    }

    @Test
    void regradeIsVisibleOnTheNextLoad() {
        concepts.replaceQuizConcepts(ACTIVE, CHOICE_QUIZ, List.of("변수"), "TEACHER");
        jdbc.update("UPDATE quiz_answers SET correct = true WHERE id = 5002");
        try {
            MasteryStore.Snapshot snapshot = mastery.load(ACTIVE);
            assertThat(snapshot.observations()).filteredOn(o -> o.userId() == LEE && o.correct() != null)
                    .extracting(MasteryStore.Observation::correct).containsExactly(true);
        } finally {
            jdbc.update("UPDATE quiz_answers SET correct = false WHERE id = 5002");
        }
    }

    // ------------------------------------------------------------------ added by the developer (Round 1 F2, F9, F10)

    @Test
    void conceptsNoQuizUsesAnyMoreAreRemovedOnReplace() {
        concepts.replaceQuizConcepts(ACTIVE, CHOICE_QUIZ, List.of("변수", "반복문"), "TEACHER");
        concepts.replaceQuizConcepts(ACTIVE, TEXT_QUIZ, List.of("변수"), "TEACHER");

        concepts.replaceQuizConcepts(ACTIVE, CHOICE_QUIZ, List.of("함수"), "TEACHER");

        // 반복문 was only on CHOICE_QUIZ -> gone; 변수 is still on TEXT_QUIZ -> kept
        assertThat(concepts.sessionConcepts(ACTIVE)).extracting(ConceptView::name)
                .containsExactlyInAnyOrder("변수", "함수");
        assertThat(concepts.quizConcepts(ACTIVE).get(TEXT_QUIZ)).extracting(QuizConceptView::name)
                .containsExactly("변수");
    }

    @Test
    void releasableConceptsAreThoseNoOtherQuizUses() {
        concepts.replaceQuizConcepts(ACTIVE, CHOICE_QUIZ, List.of("변수", "반복문"), "TEACHER");
        concepts.replaceQuizConcepts(ACTIVE, TEXT_QUIZ, List.of("변수"), "TEACHER");

        assertThat(concepts.releasableConcepts(ACTIVE, CHOICE_QUIZ)).extracting(ConceptView::name)
                .containsExactly("반복문");
        assertThat(concepts.releasableConcepts(ACTIVE, TEXT_QUIZ)).isEmpty();
        assertThat(concepts.releasableConcepts(ACTIVE, UNTAGGED_QUIZ)).isEmpty();
    }

    @Test
    void recentQuestionsSkipRowsOfUsersWhoAreNoLongerStudentsOfTheSession() {
        insertQuestion(ACTIVE, KIM, "김학생 합성 질문입니다", NOW.minusSeconds(3));
        insertQuestion(ACTIVE, PARK, "박학생 합성 질문입니다", NOW.minusSeconds(2));
        insertQuestion(ACTIVE, TEACHER, "선생님 행이 남아 있어도", NOW.minusSeconds(1));
        // PARK left while a chat insert was in flight: the row survived the listener's delete
        jdbc.update("DELETE FROM session_participants WHERE session_id = ? AND user_id = ?", ACTIVE, PARK);

        List<QuestionRow> rows = questions.recentQuestions(ACTIVE, 10);

        assertThat(rows).extracting(QuestionRow::userId).containsExactly(KIM);
    }

    @Test
    void recentQuestionsSinceSkipsRowsOlderThanTheCutoff() {
        Instant cutoff = NOW.minus(Duration.ofDays(30));
        insertQuestion(ACTIVE, KIM, "만료된 합성 질문입니다", cutoff.minusSeconds(1));
        insertQuestion(ACTIVE, LEE, "경계 시각 합성 질문", cutoff);
        insertQuestion(ACTIVE, PARK, "최근 합성 질문입니다", NOW);

        List<QuestionRow> rows = questions.recentQuestions(ACTIVE, 10, cutoff);

        assertThat(rows).extracting(r -> r.text().revealForEmbedding())
                .containsExactly("최근 합성 질문입니다", "경계 시각 합성 질문");
    }

    // ------------------------------------------------------------------ leave / kick racing with a question INSERT
    // (Codex merge review). The removal transaction mirrors StudySessionService.remove: the listener's delete runs
    // first, the participant DELETE comes later (JPA flush at commit).

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void removePark(TransactionTemplate tx, CountDownLatch beforeCommit, CountDownLatch commit) {
        tx.executeWithoutResult(status -> {
            questions.deleteBySessionAndUser(ACTIVE, PARK);
            jdbc.update("DELETE FROM session_participants WHERE session_id = ? AND user_id = ?", ACTIVE, PARK);
            beforeCommit.countDown();
            await(commit);
        });
    }

    @Test
    void insertDuringAnUncommittedRemovalWaitsAndStoresNothing() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch removed = new CountDownLatch(1);
            CountDownLatch commitRemoval = new CountDownLatch(1);
            Future<?> removal = pool.submit(() -> removePark(tx, removed, commitRemoval));
            await(removed);

            Future<Integer> insert = pool.submit(() -> questions.insertIfActiveStudent(ACTIVE, PARK, "경합 중 합성 질문"));
            Thread.sleep(300);
            assertThat(insert.isDone()).as("insert waits for the participant row lock").isFalse();

            commitRemoval.countDown();
            removal.get(10, TimeUnit.SECONDS);
            assertThat(insert.get(10, TimeUnit.SECONDS)).isZero();
            assertThat(questionCount(ACTIVE, PARK)).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void removalAfterAnUncommittedInsertWaitsAndDeletesTheNewRow() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch inserted = new CountDownLatch(1);
            CountDownLatch commitInsert = new CountDownLatch(1);
            Future<Integer> insert = pool.submit(() -> tx.execute(status -> {
                int rows = questions.insertIfActiveStudent(ACTIVE, PARK, "먼저 들어온 합성 질문");
                inserted.countDown();
                await(commitInsert);
                return rows;
            }));
            await(inserted);

            CountDownLatch removed = new CountDownLatch(1);
            Future<?> removal = pool.submit(() -> removePark(tx, removed, new CountDownLatch(0)));
            Thread.sleep(300);
            assertThat(removal.isDone()).as("removal waits for the inserting transaction").isFalse();

            commitInsert.countDown();
            assertThat(insert.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            removal.get(10, TimeUnit.SECONDS);
            assertThat(questionCount(ACTIVE, PARK)).isZero();
        } finally {
            pool.shutdownNow();
        }
    }
}
