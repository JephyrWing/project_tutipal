package com.my.tutipal_back.learning.insights.service;

import static com.my.tutipal_back.ai.quota.AiQuotaFixtures.hasRoom;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;

import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.ai.client.AiSlots;
import com.my.tutipal_back.ai.client.AiUnavailableException;
import com.my.tutipal_back.ai.client.ClusterClient;
import com.my.tutipal_back.ai.quota.AiFeature;
import com.my.tutipal_back.ai.quota.AiQuota;
import com.my.tutipal_back.ai.quota.AiQuotaExceededException;
import com.my.tutipal_back.ai.quota.AiQuotaFixtures;
import com.my.tutipal_back.ai.quota.MutableClock;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.learning.insights.dto.ConceptView;
import com.my.tutipal_back.learning.insights.dto.ConfusionsResponse;
import com.my.tutipal_back.learning.insights.repository.ConceptStore;
import com.my.tutipal_back.learning.insights.repository.QuestionRow;
import com.my.tutipal_back.learning.insights.repository.RawQuestion;
import com.my.tutipal_back.sessions.service.StudySessionService;

import tools.jackson.databind.json.JsonMapper;

/**
 * PT-87 AC-8, AC-16..18: POST confusions. Order is permission -> data check -> cache -> quota -> AI.
 * Embedding (AiClient), FastAPI clustering (ClusterClient) and the quota are test doubles; no model runs.
 *
 * <p>Each synthetic question gets a one-hot embedding at index = its row id, and each concept one at index
 * 1000 + its list position, so the fake clustering can read back which question / concept every vector is
 * no matter how the service orders, normalizes or rounds them.
 */
@ExtendWith(OutputCaptureExtension.class)
class TopicAnalysisServiceTest {

    private static final long TEACHER = 1L;
    private static final long SESSION = 10L;
    private static final String MARKER = "SECRET-Q-4242";
    private static final String ALGORITHM = "kmeans-silhouette-v2";
    private static final long VARS = 100L;
    private static final long LOOPS = 200L;
    private static final long FUNCS = 300L;

    private final StudySessionService sessions = mock(StudySessionService.class);
    private final SessionQuestionSource questions = mock(SessionQuestionSource.class);
    private final ConceptStore concepts = mock(ConceptStore.class);
    private final AiClient ai = mock(AiClient.class);
    private final ClusterClient cluster = mock(ClusterClient.class);
    private final AiSlots slots = mock(AiSlots.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-25T00:00:00Z"));
    private AiQuota quota = AiQuotaFixtures.quota(5, Duration.ofHours(1), clock);
    private TopicAnalysisService service;

    private final List<QuestionRow> rows = new ArrayList<>();
    private final Map<String, List<Long>> rowsOfGroup = new LinkedHashMap<>();
    private final Map<String, float[]> vectorByText = new HashMap<>();
    private List<ConceptView> sessionConcepts = List.of(
            new ConceptView(VARS, "변수"), new ConceptView(LOOPS, "반복문"), new ConceptView(FUNCS, "함수"));
    private final List<List<String>> embedCalls = new ArrayList<>();
    private final Deque<String> models = new ArrayDeque<>();
    /** Clusters the fake FastAPI returns: group name -> matched concept id (null = no match). */
    private final Map<String, Long> clusterPlan = new LinkedHashMap<>();
    private String clusterStatus = "OK";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void collaborators() {
        when(slots.run(any(Supplier.class))).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
        when(slots.run(any(Duration.class), any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        when(questions.recentQuestions(eq(SESSION), anyInt())).thenAnswer(inv -> List.copyOf(rows));
        when(concepts.sessionConcepts(SESSION)).thenAnswer(inv -> sessionConcepts);
        when(ai.embedWithModel(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            embedCalls.add(List.copyOf(texts));
            List<float[]> vectors = new ArrayList<>();
            for (String text : texts) {
                float[] v = vectorByText.get(text);
                assertThat(v).as("embedding requested for unknown text").isNotNull();
                vectors.add(v.clone());
            }
            return new AiClient.EmbedResponse(models.isEmpty() ? "ollama:bge-m3:1024" : models.poll(), vectors);
        });
        when(cluster.cluster(anyList(), anyList())).thenAnswer(inv -> fakeCluster(inv.getArgument(0), inv.getArgument(1)));
        rebuild();
    }

    private void rebuild() {
        service = new TopicAnalysisService(sessions, questions, concepts, ai, cluster, slots, quota,
                new TopicResultCache(clock));
    }

    // ------------------------------------------------------------------ fixtures

    private static float[] oneHot(int index) {
        float[] v = new float[1024];
        v[index] = 1f;
        return v;
    }

    private static int hotIndex(float[] v) {
        int best = 0;
        for (int i = 1; i < v.length; i++) {
            if (v[i] > v[best]) {
                best = i;
            }
        }
        return best;
    }

    /** One question per listed user, all in the given topic group. */
    private void ask(String group, long... users) {
        for (long user : users) {
            long id = rows.size() + 1;
            String text = MARKER + " " + group + " 질문이 헷갈려요 " + id;
            rows.add(new QuestionRow(id, user, new RawQuestion(text)));
            vectorByText.put(text, oneHot((int) id));
            rowsOfGroup.computeIfAbsent(group, g -> new ArrayList<>()).add(id);
        }
    }

    private void askShort(long user, String text) {
        long id = rows.size() + 1;
        rows.add(new QuestionRow(id, user, new RawQuestion(text)));
        vectorByText.put(text, oneHot((int) id));
    }

    private void conceptVectors() {
        for (int i = 0; i < sessionConcepts.size(); i++) {
            vectorByText.put(sessionConcepts.get(i).name(), oneHot(1000 + i));
        }
    }

    private ClusterClient.Result fakeCluster(List<float[]> vectors, List<float[]> conceptVectors) {
        Map<Long, Integer> indexOfRow = new HashMap<>();
        for (int i = 0; i < vectors.size(); i++) {
            indexOfRow.put((long) hotIndex(vectors.get(i)), i);
        }
        Map<Long, Integer> indexOfConcept = new HashMap<>();
        for (int j = 0; j < conceptVectors.size(); j++) {
            int position = hotIndex(conceptVectors.get(j)) - 1000;
            indexOfConcept.put(sessionConcepts.get(position).id(), j);
        }
        List<ClusterClient.Cluster> clusters = new ArrayList<>();
        clusterPlan.forEach((group, conceptId) -> {
            List<Integer> members = rowsOfGroup.get(group).stream().map(indexOfRow::get).toList();
            assertThat(members).as("every question of group %s was sent", group).doesNotContainNull();
            ClusterClient.ConceptMatch match = conceptId == null ? null
                    : new ClusterClient.ConceptMatch(indexOfConcept.get(conceptId), 0.8);
            clusters.add(new ClusterClient.Cluster(members, match));
        });
        return new ClusterClient.Result(clusterStatus, ALGORITHM, clusters.size(), 0.42, clusters);
    }

    /**
     * 22 questions from 6 students.
     * 변수 = A{1,1,2} + B{3}: each alone has fewer than 3 students, merged 3 students / 4 questions.
     * 반복문 = C{1,2,3,4,4} + D{2,3,4,5}: merged 5 distinct students (not 4+4) / 9 questions.
     * E{5,6}: 2 students -> dropped. F{1,2,3,6}: 4 students / 4 questions. G{4,5,6}: 3 students / 3 questions.
     * Expected TOP3: 반복문(5,9), F(4,4), 변수(3,4) — 변수 beats G on question count.
     */
    private void standardScenario() {
        ask("A", 1, 1, 2);
        ask("B", 3);
        ask("C", 1, 2, 3, 4, 4);
        ask("D", 2, 3, 4, 5);
        ask("E", 5, 6);
        ask("F", 1, 2, 3, 6);
        ask("G", 4, 5, 6);
        conceptVectors();
        // FastAPI order is deliberately not the final ranking
        clusterPlan.put("E", null);
        clusterPlan.put("A", VARS);
        clusterPlan.put("G", null);
        clusterPlan.put("C", LOOPS);
        clusterPlan.put("F", null);
        clusterPlan.put("B", VARS);
        clusterPlan.put("D", LOOPS);
    }

    private static void assertUnavailable(Throwable t) {
        if (t instanceof ApiException api) {
            assertThat(api.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        } else {
            assertThat(t).isInstanceOf(AiUnavailableException.class);
        }
    }

    private int questionTextsEmbedded() {
        return embedCalls.stream().mapToInt(call -> (int) call.stream().filter(t -> t.startsWith(MARKER)).count()).sum();
    }

    // ------------------------------------------------------------------ AC-16 permission

    @Test
    void permissionIsCheckedBeforeReadingQuestions() {
        standardScenario();
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "선생님만")).when(sessions).requireTeacherOf(TEACHER, SESSION);

        assertThatThrownBy(() -> service.analyze(TEACHER, SESSION))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(questions, ai, cluster);
        assertThat(hasRoom(quota, TEACHER, AiFeature.INSIGHTS)).isTrue();
    }

    @Test
    void otherOrMissingSessionIsNotFound() {
        doThrow(new ApiException(HttpStatus.NOT_FOUND, "방을 찾을 수 없어요")).when(sessions)
                .requireTeacherOf(TEACHER, SESSION);

        assertThatThrownBy(() -> service.analyze(TEACHER, SESSION))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(questions, ai, cluster);
    }

    // ------------------------------------------------------------------ AC-17 insufficient data

    @Test
    void fewerThanTenQuestionsIsInsufficientWithoutAiOrQuota() {
        ask("X", 1, 2, 3, 1, 2, 3, 1, 2, 3);   // 9 questions, 3 students

        ConfusionsResponse r = service.analyze(TEACHER, SESSION);

        assertThat(r.status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(r.reason()).isEqualTo("FEW_QUESTIONS");
        assertThat(r.questionCount()).isEqualTo(9);
        assertThat(r.studentCount()).isEqualTo(3);
        assertThat(r.topics()).isEmpty();
        verifyNoInteractions(ai, cluster);
        assertThat(hasRoom(quota, TEACHER, AiFeature.INSIGHTS)).isTrue();
    }

    @Test
    void fewerThanThreeStudentsIsInsufficientWithoutAiOrQuota() {
        ask("X", 1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 1, 2);   // 12 questions, 2 students

        ConfusionsResponse r = service.analyze(TEACHER, SESSION);

        assertThat(r.status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(r.reason()).isEqualTo("FEW_STUDENTS");
        assertThat(r.studentCount()).isEqualTo(2);
        verifyNoInteractions(ai, cluster);
        assertThat(hasRoom(quota, TEACHER, AiFeature.INSIGHTS)).isTrue();
    }

    @Test
    void questionsShorterThanFiveCharactersDoNotCount() {
        ask("X", 1, 2, 3, 1, 2, 3, 1, 2, 3);   // 9 real questions
        askShort(1, "ㅇㅇ");
        askShort(2, "네네");
        askShort(4, "  왜요  ");                 // 2 characters after trimming; a 4th student only by short text

        ConfusionsResponse r = service.analyze(TEACHER, SESSION);

        assertThat(r.status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(r.questionCount()).isEqualTo(9);
        assertThat(r.studentCount()).isEqualTo(3);
        verifyNoInteractions(ai, cluster);
    }

    @Test
    void insufficientDataIsAnsweredEvenWhenTheQuotaIsUsedUp() {
        quota = AiQuotaFixtures.quota(1, Duration.ofHours(1), clock);
        rebuild();
        quota.reserve(TEACHER, AiFeature.INSIGHTS).run(() -> null);
        ask("X", 1, 2, 3);

        assertThat(service.analyze(TEACHER, SESSION).status()).isEqualTo("INSUFFICIENT_DATA");
        verifyNoInteractions(ai, cluster);
    }

    // ------------------------------------------------------------------ AC-18 post-processing

    @Test
    void mergesSameConceptClustersRecountsStudentsDropsSmallTopicsAndRanksTopThree() {
        standardScenario();

        ConfusionsResponse r = service.analyze(TEACHER, SESSION);

        assertThat(r.status()).isEqualTo("OK");
        assertThat(r.reason()).isNull();
        assertThat(r.questionCount()).isEqualTo(22);
        assertThat(r.studentCount()).isEqualTo(6);
        assertThat(r.algorithm()).isEqualTo(ALGORITHM);
        assertThat(r.k()).isEqualTo(7);
        assertThat(r.silhouette()).isEqualTo(0.42);
        assertThat(r.cached()).isFalse();
        assertThat(r.topics()).hasSize(3);
        assertThat(r.topics()).extracting(ConfusionsResponse.Topic::rank).containsExactly(1, 2, 3);

        ConfusionsResponse.Topic first = r.topics().get(0);
        assertThat(first.label()).isEqualTo("반복문");
        assertThat(first.conceptId()).isEqualTo(LOOPS);
        assertThat(first.studentCount()).isEqualTo(5);
        assertThat(first.questionCount()).isEqualTo(9);

        ConfusionsResponse.Topic second = r.topics().get(1);
        assertThat(second.conceptId()).isNull();
        assertThat(second.label()).matches("태그되지 않은 주제 \\d+");
        assertThat(second.studentCount()).isEqualTo(4);
        assertThat(second.questionCount()).isEqualTo(4);

        ConfusionsResponse.Topic third = r.topics().get(2);
        assertThat(third.label()).isEqualTo("변수");
        assertThat(third.conceptId()).isEqualTo(VARS);
        assertThat(third.studentCount()).isEqualTo(3);
        assertThat(third.questionCount()).isEqualTo(4);
    }

    @Test
    void untaggedTopicsGetDistinctNumberedLabels() {
        ask("P", 1, 2, 3, 4, 5);
        ask("Q", 1, 2, 3, 4);
        ask("R", 1, 2, 3);
        conceptVectors();
        clusterPlan.put("P", null);
        clusterPlan.put("Q", null);
        clusterPlan.put("R", null);

        ConfusionsResponse r = service.analyze(TEACHER, SESSION);

        assertThat(r.topics()).hasSize(3);
        assertThat(r.topics()).allSatisfy(t -> {
            assertThat(t.conceptId()).isNull();
            assertThat(t.label()).matches("태그되지 않은 주제 \\d+");
        });
        assertThat(r.topics()).extracting(ConfusionsResponse.Topic::label).doesNotHaveDuplicates();
        assertThat(r.topics()).extracting(ConfusionsResponse.Topic::studentCount).containsExactly(5, 4, 3);
    }

    @Test
    void noTopicSharedByThreeStudentsGivesNoTopicsAndReason() {
        ask("P", 1, 2, 1, 2, 1);
        ask("Q", 3, 4, 3, 4, 3);
        conceptVectors();
        clusterPlan.put("P", null);
        clusterPlan.put("Q", LOOPS);

        ConfusionsResponse r = service.analyze(TEACHER, SESSION);

        assertThat(r.topics()).isEmpty();
        assertThat(r.reason()).isEqualTo("NO_SHARED_TOPIC");
    }

    @Test
    void lowQualityFromFastApiIsPassedThrough() {
        standardScenario();
        clusterStatus = "LOW_QUALITY";

        assertThat(service.analyze(TEACHER, SESSION).status()).isEqualTo("LOW_QUALITY");
    }

    // ------------------------------------------------------------------ AC-17 cache and quota

    @Test
    void analysisUsesOneInsightsQuota() {
        quota = AiQuotaFixtures.quota(1, Duration.ofHours(1), clock);
        rebuild();
        standardScenario();

        service.analyze(TEACHER, SESSION);

        assertThat(hasRoom(quota, TEACHER, AiFeature.INSIGHTS)).isFalse();
        verify(cluster, times(1)).cluster(anyList(), anyList());
    }

    @Test
    void cacheHitNeedsNoQuotaAndNoAiCall() {
        quota = AiQuotaFixtures.quota(1, Duration.ofHours(1), clock);
        rebuild();
        standardScenario();

        ConfusionsResponse first = service.analyze(TEACHER, SESSION);
        int embedsAfterFirst = embedCalls.size();
        ConfusionsResponse second = service.analyze(TEACHER, SESSION);   // quota is used up: only a hit can answer

        assertThat(first.cached()).isFalse();
        assertThat(second.cached()).isTrue();
        assertThat(second.topics()).isEqualTo(first.topics());
        assertThat(embedCalls).hasSize(embedsAfterFirst);
        verify(cluster, times(1)).cluster(anyList(), anyList());
    }

    @Test
    void quotaUsedUpWithoutCacheHitIsRejectedBeforeAi() {
        quota = AiQuotaFixtures.quota(1, Duration.ofHours(1), clock);
        rebuild();
        quota.reserve(TEACHER, AiFeature.INSIGHTS).run(() -> null);
        standardScenario();

        assertThatThrownBy(() -> service.analyze(TEACHER, SESSION))
                .isInstanceOfSatisfying(AiQuotaExceededException.class,
                        e -> assertThat(e.getFeature()).isEqualTo(AiFeature.INSIGHTS));
        verifyNoInteractions(ai, cluster);
    }

    @Test
    void newQuestionMissesTheCache() {
        standardScenario();
        service.analyze(TEACHER, SESSION);

        ask("F", 4);   // one more question
        ConfusionsResponse again = service.analyze(TEACHER, SESSION);

        assertThat(again.cached()).isFalse();
        verify(cluster, times(2)).cluster(anyList(), anyList());
    }

    @Test
    void changedConceptSetMissesTheCache() {
        standardScenario();
        service.analyze(TEACHER, SESSION);

        sessionConcepts = List.of(new ConceptView(VARS, "변수"), new ConceptView(LOOPS, "반복문"),
                new ConceptView(FUNCS, "함수"), new ConceptView(400L, "재귀"));
        conceptVectors();
        ConfusionsResponse again = service.analyze(TEACHER, SESSION);

        assertThat(again.cached()).isFalse();
        verify(cluster, times(2)).cluster(anyList(), anyList());
    }

    // ------------------------------------------------------------------ AC-17 embedding model and AI failure

    @Test
    void mixedEmbeddingModelsAcrossQuestionBatchesIs503() {
        long[] users = new long[70];
        for (int i = 0; i < users.length; i++) {
            users[i] = i % 7 + 1;
        }
        ask("X", users);   // 70 questions -> at least two batches of at most 64
        sessionConcepts = List.of();
        models.add("ollama:bge-m3:1024");
        models.add("ollama:other-model:1024");

        assertThatThrownBy(() -> service.analyze(TEACHER, SESSION))
                .satisfies(TopicAnalysisServiceTest::assertUnavailable);
        verify(cluster, never()).cluster(anyList(), anyList());
    }

    @Test
    void conceptEmbeddingFromAnotherModelIs503() {
        standardScenario();
        models.add("ollama:bge-m3:1024");
        models.add("ollama:other-model:1024");

        assertThatThrownBy(() -> service.analyze(TEACHER, SESSION))
                .satisfies(TopicAnalysisServiceTest::assertUnavailable);
        verify(cluster, never()).cluster(anyList(), anyList());
    }

    @Test
    void embedsAtMost256QuestionsInBatchesOfAtMost64() {
        long[] users = new long[300];
        for (int i = 0; i < users.length; i++) {
            users[i] = i % 10 + 1;
        }
        ask("X", users);
        sessionConcepts = List.of();
        clusterPlan.put("X", null);
        doAnswer(inv -> {
            List<float[]> vectors = inv.getArgument(0);
            assertThat(vectors).hasSizeBetween(10, 256);
            return new ClusterClient.Result("OK", ALGORITHM, 1, null,
                    List.of(new ClusterClient.Cluster(IntStream.range(0, vectors.size()).boxed().toList(), null)));
        }).when(cluster).cluster(anyList(), anyList());

        service.analyze(TEACHER, SESSION);

        assertThat(questionTextsEmbedded()).isEqualTo(256);
        assertThat(embedCalls).allSatisfy(call -> assertThat(call).hasSizeLessThanOrEqualTo(64));
        assertThat(embedCalls.stream().filter(call -> call.stream().anyMatch(t -> t.startsWith(MARKER))).count())
                .isLessThanOrEqualTo(4);
    }

    @Test
    void aiFailureIs503AndIsNotCached() {
        standardScenario();
        doThrow(new AiUnavailableException()).when(cluster).cluster(anyList(), anyList());

        assertThatThrownBy(() -> service.analyze(TEACHER, SESSION))
                .satisfies(TopicAnalysisServiceTest::assertUnavailable);

        doAnswer(inv -> fakeCluster(inv.getArgument(0), inv.getArgument(1))).when(cluster).cluster(anyList(), anyList());
        ConfusionsResponse retry = service.analyze(TEACHER, SESSION);
        assertThat(retry.cached()).isFalse();
        assertThat(retry.status()).isEqualTo("OK");
    }

    // ------------------------------------------------------------------ AC-8 privacy

    @Test
    void responseHasNoQuestionTextAndNoStudentIdentifiers(CapturedOutput output) {
        standardScenario();

        ConfusionsResponse r = service.analyze(TEACHER, SESSION);
        String json = JsonMapper.builder().build().writeValueAsString(r);

        assertThat(json).doesNotContain(MARKER).doesNotContain("userId").doesNotContain("members");
        assertThat(output.getAll()).doesNotContain(MARKER);
        Set<String> names = new HashSet<>();
        for (RecordComponent c : ConfusionsResponse.Topic.class.getRecordComponents()) {
            names.add(c.getName());
        }
        assertThat(names).containsExactlyInAnyOrder("rank", "label", "conceptId", "questionCount", "studentCount");
    }

    @Test
    void failureLogsDoNotContainQuestionText(CapturedOutput output) {
        standardScenario();
        doThrow(new IllegalStateException("cluster call failed")).when(cluster).cluster(anyList(), anyList());

        assertThatThrownBy(() -> service.analyze(TEACHER, SESSION)).isInstanceOf(RuntimeException.class);

        assertThat(output.getAll()).doesNotContain(MARKER);
    }

    @Test
    void readsQuestionsAndConceptsOfTheSessionAfterTheTeacherCheck() {
        standardScenario();

        service.analyze(TEACHER, SESSION);

        verify(cluster).cluster(anyList(), anyList());
        verify(questions).recentQuestions(eq(SESSION), anyInt());
        verify(concepts).sessionConcepts(SESSION);
        verify(sessions).requireTeacherOf(TEACHER, SESSION);
        verify(sessions, never()).requireActiveRole(anyLong(), anyLong());
    }
}
