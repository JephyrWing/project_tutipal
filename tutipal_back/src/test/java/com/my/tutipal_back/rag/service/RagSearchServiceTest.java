package com.my.tutipal_back.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;

import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.ai.client.AiUnavailableException;
import com.my.tutipal_back.ai.client.RerankClient;
import com.my.tutipal_back.ai.client.RerankUnavailableException;
import com.my.tutipal_back.rag.config.RagRerankProperties;
import com.my.tutipal_back.rag.dto.RagHit;
import com.my.tutipal_back.rag.repository.RagSearchRepository;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * PT-86: RAG search with an optional cross-encoder rerank step.
 * Disabled = today's cosine top-limit; enabled = N candidates -> 0.5 filter -> rerank (reorder only) -> top limit.
 * Every rerank failure falls back to the cosine order. similarity always stays the cosine value.
 */
class RagSearchServiceTest {

    private static final long SESSION = 7L;
    private static final String MODEL = "bge-m3";
    private static final String QUERY = "버블 정렬의 시간 복잡도는?";
    private static final int LIMIT = 3;

    private final AiClient ai = mock(AiClient.class);
    private final RagSearchRepository repository = mock(RagSearchRepository.class);
    private final RerankClient reranker = mock(RerankClient.class);

    private ListAppender<ILoggingEvent> logs;
    private Logger searchLogger;

    @BeforeEach
    void setUp() {
        when(repository.hasChunks(SESSION)).thenReturn(true);
        when(ai.embedWithModel(List.of(QUERY))).thenReturn(new AiClient.EmbedResponse(MODEL, List.of(vector())));
        when(reranker.isConfigured()).thenReturn(true);

        searchLogger = (Logger) LoggerFactory.getLogger("com.my.tutipal_back.materials.service.MaterialService");
        logs = new ListAppender<>();
        logs.start();
        searchLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        searchLogger.detachAppender(logs);
    }

    private static float[] vector() {
        float[] v = new float[1024];
        v[0] = 1;
        return v;
    }

    private static RagHit hit(int i, double similarity) {
        return new RagHit("file-" + i + ".pdf", i, "content-" + i, similarity);
    }

    /** n hits in cosine order (descending similarity, all >= 0.5). */
    private static List<RagHit> cosineOrdered(int n) {
        return IntStream.range(0, n).mapToObj(i -> hit(i, 0.95 - i * 0.01)).toList();
    }

    private static RagRerankProperties enabled(int candidates, int minCandidates) {
        return new RagRerankProperties(true, candidates, Duration.ofSeconds(2), minCandidates);
    }

    private static RagRerankProperties disabled() {
        return new RagRerankProperties(false, 20, Duration.ofSeconds(2), 2);
    }

    private RagSearchService service(RagRerankProperties props) {
        return new RagSearchService(ai, repository, reranker, props);
    }

    private void repositoryReturns(int limit, List<RagHit> hits) {
        when(repository.findNearest(eq(SESSION), eq(MODEL), anyString(), eq(limit))).thenReturn(hits);
    }

    // ---- disabled == baseline ----

    @Test
    void disabledAsksRepositoryForLimitAndKeepsOnlySimilarityAtLeastHalf() {
        repositoryReturns(LIMIT, List.of(hit(0, 0.9), hit(1, 0.5), hit(2, 0.49)));

        List<RagHit> result = service(disabled()).search(SESSION, QUERY, LIMIT);

        assertThat(result).containsExactly(hit(0, 0.9), hit(1, 0.5));
        verify(repository).findNearest(eq(SESSION), eq(MODEL), anyString(), eq(LIMIT));
        verifyNoInteractions(reranker);
    }

    @Test
    void disabledNeverCallsRerankEvenWithManyCandidates() {
        repositoryReturns(LIMIT, cosineOrdered(3));

        List<RagHit> result = service(disabled()).search(SESSION, QUERY, LIMIT);

        assertThat(result).containsExactlyElementsOf(cosineOrdered(3));
        verifyNoInteractions(reranker);
    }

    // ---- enabled: candidate count ----

    @Test
    void enabledAsksRepositoryForConfiguredCandidates() {
        repositoryReturns(20, cosineOrdered(5));
        when(reranker.rerank(eq(QUERY), anyList())).thenReturn(new double[] {0.1, 0.2, 0.3, 0.4, 0.5});

        service(enabled(20, 2)).search(SESSION, QUERY, LIMIT);

        verify(repository).findNearest(eq(SESSION), eq(MODEL), anyString(), eq(20));
    }

    @Test
    void candidatesAboveThirtyTwoAreClampedToThirtyTwo() {
        repositoryReturns(32, List.of());

        service(enabled(100, 2)).search(SESSION, QUERY, LIMIT);

        verify(repository).findNearest(eq(SESSION), eq(MODEL), anyString(), eq(32));
    }

    @Test
    void candidatesBelowOneAreClampedToOne() {
        repositoryReturns(1, List.of());

        service(enabled(0, 2)).search(SESSION, QUERY, LIMIT);

        verify(repository).findNearest(eq(SESSION), eq(MODEL), anyString(), eq(1));
    }

    // ---- enabled: no-result and small candidate sets ----

    @Test
    void zeroHitsAfterFilterReturnsEmptyWithoutRerankCall() {
        repositoryReturns(20, List.of(hit(0, 0.49), hit(1, 0.2)));

        List<RagHit> result = service(enabled(20, 2)).search(SESSION, QUERY, LIMIT);

        assertThat(result).isEmpty();
        verify(reranker, never()).rerank(anyString(), anyList());
    }

    @Test
    void emptyRepositoryResultReturnsEmptyWithoutRerankCall() {
        repositoryReturns(20, List.of());

        assertThat(service(enabled(20, 2)).search(SESSION, QUERY, LIMIT)).isEmpty();
        verify(reranker, never()).rerank(anyString(), anyList());
    }

    @Test
    void fewerThanMinCandidatesKeepsCosineOrderWithoutRerankCall() {
        // 3 candidates but only 1 survives the filter, min-candidates = 2
        repositoryReturns(20, List.of(hit(0, 0.8), hit(1, 0.3), hit(2, 0.1)));

        List<RagHit> result = service(enabled(20, 2)).search(SESSION, QUERY, LIMIT);

        assertThat(result).containsExactly(hit(0, 0.8));
        verify(reranker, never()).rerank(anyString(), anyList());
    }

    @Test
    void fewerThanMinCandidatesStillTruncatesToLimitInCosineOrder() {
        repositoryReturns(20, cosineOrdered(4));

        List<RagHit> result = service(enabled(20, 5)).search(SESSION, QUERY, LIMIT);

        assertThat(result).containsExactlyElementsOf(cosineOrdered(4).subList(0, LIMIT));
        verify(reranker, never()).rerank(anyString(), anyList());
    }

    // ---- enabled: rerank reorders and truncates ----

    @Test
    void rerankReordersByScoreDescendingAndTruncatesToLimit() {
        List<RagHit> candidates = cosineOrdered(5);
        repositoryReturns(20, candidates);
        when(reranker.rerank(eq(QUERY), anyList())).thenReturn(new double[] {0.1, 0.9, 0.3, 0.8, 0.95});

        List<RagHit> result = service(enabled(20, 2)).search(SESSION, QUERY, LIMIT);

        assertThat(result).containsExactly(candidates.get(4), candidates.get(1), candidates.get(3));
    }

    @Test
    void rerankReceivesOnlyFilteredCandidateContentsInCosineOrder() {
        repositoryReturns(20, List.of(hit(0, 0.9), hit(1, 0.7), hit(2, 0.4)));
        when(reranker.rerank(eq(QUERY), anyList())).thenReturn(new double[] {0.2, 0.1});

        List<RagHit> result = service(enabled(20, 2)).search(SESSION, QUERY, LIMIT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> documents = ArgumentCaptor.forClass(List.class);
        verify(reranker).rerank(eq(QUERY), documents.capture());
        assertThat(documents.getValue()).containsExactly("content-0", "content-1");
        assertThat(result).containsExactly(hit(0, 0.9), hit(1, 0.7));
    }

    @Test
    void tiesInRerankScoreKeepCosineOrder() {
        List<RagHit> candidates = cosineOrdered(4);
        repositoryReturns(20, candidates);
        when(reranker.rerank(eq(QUERY), anyList())).thenReturn(new double[] {0.5, 0.7, 0.5, 0.7});

        List<RagHit> result = service(enabled(20, 2)).search(SESSION, QUERY, LIMIT);

        assertThat(result).containsExactly(candidates.get(1), candidates.get(3), candidates.get(0));
    }

    @Test
    void neverReturnsMoreThanThreeSourcesForTutorLimit() {
        repositoryReturns(32, cosineOrdered(32));
        double[] scores = new double[32];
        for (int i = 0; i < scores.length; i++) {
            scores[i] = i;
        }
        when(reranker.rerank(eq(QUERY), anyList())).thenReturn(scores);

        List<RagHit> result = service(enabled(32, 2)).search(SESSION, QUERY, 3);

        assertThat(result).hasSize(3);
        assertThat(result).containsExactly(cosineOrdered(32).get(31), cosineOrdered(32).get(30), cosineOrdered(32).get(29));
    }

    @Test
    void rerankedResultIsAlwaysSubsetOfRepositoryCandidates() {
        List<RagHit> candidates = cosineOrdered(6);
        repositoryReturns(20, candidates);
        when(reranker.rerank(eq(QUERY), anyList())).thenReturn(new double[] {3, 1, 4, 1, 5, 9});

        List<RagHit> result = service(enabled(20, 2)).search(SESSION, QUERY, LIMIT);

        assertThat(candidates).containsAll(result);
        assertThat(result).doesNotHaveDuplicates();
    }

    @Test
    void similarityStaysCosineAfterRerank() {
        List<RagHit> candidates = List.of(hit(0, 0.91), hit(1, 0.72), hit(2, 0.63));
        repositoryReturns(20, candidates);
        when(reranker.rerank(eq(QUERY), anyList())).thenReturn(new double[] {-4.0, 7.5, 2.25});

        List<RagHit> result = service(enabled(20, 2)).search(SESSION, QUERY, LIMIT);

        assertThat(result).extracting(RagHit::similarity).containsExactly(0.72, 0.63, 0.91);
        assertThat(result).extracting(RagHit::source).containsExactly("file-1.pdf p.1", "file-2.pdf p.2", "file-0.pdf p.0");
    }

    // ---- fallback: every reason -> cosine order top limit ----

    @Test
    void rerankExceptionFallsBackToCosineTopLimit() {
        repositoryReturns(20, cosineOrdered(5));
        when(reranker.rerank(eq(QUERY), anyList())).thenThrow(new RerankUnavailableException("status"));

        assertThat(service(enabled(20, 2)).search(SESSION, QUERY, LIMIT))
                .containsExactlyElementsOf(cosineOrdered(5).subList(0, LIMIT));
    }

    @Test
    void rerankTimeoutFallsBackToCosineTopLimit() {
        repositoryReturns(20, cosineOrdered(5));
        when(reranker.rerank(eq(QUERY), anyList())).thenThrow(new RerankUnavailableException("timeout"));

        assertThat(service(enabled(20, 2)).search(SESSION, QUERY, LIMIT))
                .containsExactlyElementsOf(cosineOrdered(5).subList(0, LIMIT));
    }

    @Test
    void unexpectedRuntimeExceptionFromRerankFallsBackToCosineTopLimit() {
        repositoryReturns(20, cosineOrdered(5));
        when(reranker.rerank(eq(QUERY), anyList())).thenThrow(new ResourceAccessException("read timed out"));

        assertThat(service(enabled(20, 2)).search(SESSION, QUERY, LIMIT))
                .containsExactlyElementsOf(cosineOrdered(5).subList(0, LIMIT));
    }

    @Test
    void scoresLengthMismatchFallsBackToCosineTopLimit() {
        repositoryReturns(20, cosineOrdered(5));
        when(reranker.rerank(eq(QUERY), anyList())).thenReturn(new double[] {0.9, 0.8});

        assertThat(service(enabled(20, 2)).search(SESSION, QUERY, LIMIT))
                .containsExactlyElementsOf(cosineOrdered(5).subList(0, LIMIT));
    }

    @Test
    void nullScoresFallBackToCosineTopLimit() {
        repositoryReturns(20, cosineOrdered(5));
        when(reranker.rerank(eq(QUERY), anyList())).thenReturn(null);

        assertThat(service(enabled(20, 2)).search(SESSION, QUERY, LIMIT))
                .containsExactlyElementsOf(cosineOrdered(5).subList(0, LIMIT));
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void nonFiniteScoreFallsBackToCosineTopLimit(double bad) {
        repositoryReturns(20, cosineOrdered(5));
        when(reranker.rerank(eq(QUERY), anyList())).thenReturn(new double[] {0.1, 0.2, bad, 0.4, 0.5});

        assertThat(service(enabled(20, 2)).search(SESSION, QUERY, LIMIT))
                .containsExactlyElementsOf(cosineOrdered(5).subList(0, LIMIT));
    }

    @Test
    void unconfiguredRerankClientFallsBackToCosineWithoutCallingRerank() {
        when(reranker.isConfigured()).thenReturn(false);
        repositoryReturns(20, cosineOrdered(5));

        assertThat(service(enabled(20, 2)).search(SESSION, QUERY, LIMIT))
                .containsExactlyElementsOf(cosineOrdered(5).subList(0, LIMIT));
        verify(reranker, never()).rerank(anyString(), anyList());
    }

    @Test
    void fallbackLogNeverContainsQueryOrContent() {
        repositoryReturns(20, cosineOrdered(5));
        when(reranker.rerank(eq(QUERY), anyList())).thenThrow(new RerankUnavailableException("timeout"));

        service(enabled(20, 2)).search(SESSION, QUERY, LIMIT);

        List<String> messages = new ArrayList<>();
        for (ILoggingEvent event : logs.list) {
            messages.add(event.getFormattedMessage());
        }
        assertThat(String.join("\n", messages)).doesNotContain(QUERY).doesNotContain("content-");
    }

    // ---- embedding / repository failure still -> empty ----

    @Test
    void embeddingFailureReturnsEmptyWithoutSearchOrRerank() {
        when(ai.embedWithModel(any())).thenThrow(new AiUnavailableException());

        assertThat(service(enabled(20, 2)).search(SESSION, QUERY, LIMIT)).isEmpty();
        verify(repository, never()).findNearest(any(), any(), any(), anyInt());
        verifyNoInteractions(reranker);
    }

    @Test
    void repositoryFailureReturnsEmptyWithoutRerank() {
        when(repository.findNearest(any(), any(), any(), anyInt())).thenThrow(new IllegalStateException("db down"));

        assertThat(service(enabled(20, 2)).search(SESSION, QUERY, LIMIT)).isEmpty();
        verify(reranker, never()).rerank(anyString(), anyList());
    }

    @Test
    void sessionWithoutIndexedMaterialReturnsEmptyWithoutEmbedding() {
        when(repository.hasChunks(SESSION)).thenReturn(false);

        assertThat(service(enabled(20, 2)).search(SESSION, QUERY, LIMIT)).isEmpty();
        verifyNoInteractions(ai, reranker);
    }

    @Test
    void blankQueryReturnsEmptyWithoutEmbedding() {
        assertThat(service(enabled(20, 2)).search(SESSION, "   ", LIMIT)).isEmpty();
        verifyNoInteractions(ai, reranker);
    }

    // ---- no DB connection held while waiting for the rerank HTTP call (spec 3) ----

    @Test
    void searchIsNotWrappedInATransaction() throws NoSuchMethodException {
        assertThat(RagSearchService.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(RagSearchService.class.getMethod("search", Long.class, String.class, int.class)
                .isAnnotationPresent(Transactional.class)).isFalse();
    }

    // ---- session isolation ----

    @Test
    void repositoryIsQueriedOnlyWithGivenSessionAndEmbeddingModel() {
        repositoryReturns(20, cosineOrdered(4));
        when(reranker.rerank(eq(QUERY), anyList())).thenReturn(new double[] {0.4, 0.3, 0.2, 0.1});

        service(enabled(20, 2)).search(SESSION, QUERY, LIMIT);

        verify(repository).hasChunks(SESSION);
        verify(repository).findNearest(eq(SESSION), eq(MODEL), anyString(), eq(20));
        verifyNoMoreInteractions(repository);
    }

    @Test
    void disabledPathAlsoQueriesOnlyGivenSessionAndEmbeddingModel() {
        repositoryReturns(LIMIT, cosineOrdered(3));

        service(disabled()).search(SESSION, QUERY, LIMIT);

        verify(repository).hasChunks(SESSION);
        verify(repository).findNearest(eq(SESSION), eq(MODEL), anyString(), eq(LIMIT));
        verifyNoMoreInteractions(repository);
    }
}
