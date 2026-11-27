package com.my.tutipal_back.learning.insights.service;

import static com.my.tutipal_back.ai.quota.AiQuotaFixtures.hasRoom;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.ai.client.AiSlots;
import com.my.tutipal_back.ai.client.AiUnavailableException;
import com.my.tutipal_back.ai.client.ClusterClient;
import com.my.tutipal_back.ai.quota.AiFeature;
import com.my.tutipal_back.ai.quota.AiQuota;
import com.my.tutipal_back.ai.quota.AiQuotaFixtures;
import com.my.tutipal_back.ai.quota.MutableClock;
import com.my.tutipal_back.learning.insights.dto.ConceptView;
import com.my.tutipal_back.learning.insights.dto.ConfusionsResponse;
import com.my.tutipal_back.learning.insights.repository.ConceptStore;
import com.my.tutipal_back.learning.insights.repository.QuestionRow;
import com.my.tutipal_back.learning.insights.repository.RawQuestion;
import com.my.tutipal_back.sessions.service.StudySessionService;

/**
 * PT-87 Round 1 F8 (malformed AI answer is refunded), F11 (cache key follows the analyzed id set), F12 (k = 1 has no
 * topic) and F20 (one AiSlots seat per embedding batch). All collaborators are test doubles; no model runs.
 */
class TopicAnalysisServiceRound1Test {

    private static final long TEACHER = 1L;
    private static final long SESSION = 10L;
    private static final String MODEL = "ollama:bge-m3:1024";

    private final StudySessionService sessions = mock(StudySessionService.class);
    private final SessionQuestionSource questions = mock(SessionQuestionSource.class);
    private final ConceptStore concepts = mock(ConceptStore.class);
    private final AiClient ai = mock(AiClient.class);
    private final ClusterClient cluster = mock(ClusterClient.class);
    private final AiSlots slots = mock(AiSlots.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-25T00:00:00Z"));
    private final AiQuota quota = AiQuotaFixtures.quota(1, Duration.ofHours(1), clock);
    private final TopicAnalysisService service = new TopicAnalysisService(sessions, questions, concepts, ai, cluster,
            slots, quota, new TopicResultCache(clock));
    private List<QuestionRow> rows = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void collaborators() {
        doAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get()).when(slots).run(any(Supplier.class));
        doAnswer(inv -> List.copyOf(rows)).when(questions).recentQuestions(eq(SESSION), anyInt());
        doAnswer(inv -> List.of(new ConceptView(100L, "변수"))).when(concepts).sessionConcepts(SESSION);
        doAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            List<float[]> vectors = texts.stream().map(t -> new float[1024]).toList();
            return new AiClient.EmbedResponse(MODEL, vectors);
        }).when(ai).embedWithModel(anyList());
    }

    /** One question per given row id, from four students in turn. */
    private void questionsWithIds(long... ids) {
        rows = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            rows.add(new QuestionRow(ids[i], i % 4 + 1, new RawQuestion("합성 질문 번호 " + ids[i] + " 입니다")));
        }
    }

    private void clusterAnswers(ClusterClient.Result result) {
        doAnswer(inv -> result).when(cluster).cluster(anyList(), anyList());
    }

    /** Two clusters that together cover every position 0..questions-1 exactly once. */
    private static ClusterClient.Result twoTopics(int questions) {
        int half = questions / 2;
        return new ClusterClient.Result("OK", "kmeans-silhouette-v2", 2, 0.4, List.of(
                new ClusterClient.Cluster(IntStream.range(0, half).boxed().toList(), null),
                new ClusterClient.Cluster(IntStream.range(half, questions).boxed().toList(), null)));
    }

    @Test
    void answerMissingAQuestionPositionIs503RefundedAndNotCached() {
        questionsWithIds(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        clusterAnswers(new ClusterClient.Result("OK", "kmeans-silhouette-v2", 2, 0.4, List.of(
                new ClusterClient.Cluster(IntStream.range(0, 6).boxed().toList(), null),
                new ClusterClient.Cluster(IntStream.range(6, 11).boxed().toList(), null))));   // position 11 missing

        assertThatThrownBy(() -> service.analyze(TEACHER, SESSION)).isInstanceOf(AiUnavailableException.class);
        assertThat(hasRoom(quota, TEACHER, AiFeature.INSIGHTS)).isTrue();

        clusterAnswers(twoTopics(12));
        assertThat(service.analyze(TEACHER, SESSION).cached()).isFalse();   // nothing was cached by the failure
        verify(cluster, times(2)).cluster(anyList(), anyList());
    }

    @Test
    void malformedClusterAnswerIs503AndTheQuotaIsRefunded() {
        questionsWithIds(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        clusterAnswers(new ClusterClient.Result("OK", "kmeans-silhouette-v2", 2, 0.4,
                List.of(new ClusterClient.Cluster(List.of(0, 1, 99), null))));   // 99 is not a question position

        assertThatThrownBy(() -> service.analyze(TEACHER, SESSION)).isInstanceOf(AiUnavailableException.class);

        assertThat(hasRoom(quota, TEACHER, AiFeature.INSIGHTS)).isTrue();
    }

    @Test
    void singleClusterIsNotATopic() {
        questionsWithIds(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        clusterAnswers(new ClusterClient.Result("LOW_QUALITY", "kmeans-silhouette-v2", 1, null,
                List.of(new ClusterClient.Cluster(IntStream.range(0, 12).boxed().toList(), null))));

        ConfusionsResponse r = service.analyze(TEACHER, SESSION);

        assertThat(r.status()).isEqualTo("LOW_QUALITY");
        assertThat(r.topics()).isEmpty();
        assertThat(r.reason()).isEqualTo("NO_SHARED_TOPIC");
    }

    @Test
    void sameNewestIdAndCountButDifferentQuestionsMissTheCache() {
        AiQuota roomy = AiQuotaFixtures.quota(5, Duration.ofHours(1), clock);
        TopicAnalysisService withRoom = new TopicAnalysisService(sessions, questions, concepts, ai, cluster, slots,
                roomy, new TopicResultCache(clock));
        clusterAnswers(twoTopics(12));

        questionsWithIds(20, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1);
        assertThat(withRoom.analyze(TEACHER, SESSION).cached()).isFalse();

        // a student left: question 11 is gone, an older one (0) fills the window; newest id 20 and count 12 stay
        questionsWithIds(20, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0);
        assertThat(withRoom.analyze(TEACHER, SESSION).cached()).isFalse();

        verify(cluster, times(2)).cluster(anyList(), anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyEmbeddingBatchTakesItsOwnSlot() {
        questionsWithIds(LongRange.ids(70));   // 64 + 6 questions, plus one concept batch
        clusterAnswers(twoTopics(70));

        service.analyze(TEACHER, SESSION);

        verify(slots, times(3)).run(any(Supplier.class));
        verify(ai, times(3)).embedWithModel(anyList());
    }

    private static final class LongRange {
        static long[] ids(int count) {
            return IntStream.rangeClosed(1, count).asLongStream().toArray();
        }
    }
}
