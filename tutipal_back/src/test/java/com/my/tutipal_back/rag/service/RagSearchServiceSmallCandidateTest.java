package com.my.tutipal_back.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.ai.client.RerankClient;
import com.my.tutipal_back.ai.client.RerankUnavailableException;
import com.my.tutipal_back.rag.config.RagRerankProperties;
import com.my.tutipal_back.rag.dto.RagHit;
import com.my.tutipal_back.rag.repository.RagSearchRepository;

/**
 * Codex round 3: with candidates N below limit (allowed 1..2), the cosine paths (below min-candidates, unconfigured,
 * rerank failure) must still return the cosine top `limit`, like the disabled path.
 */
class RagSearchServiceSmallCandidateTest {

    private static final long SESSION = 9L;
    private static final String QUERY = "질문";

    private final AiClient ai = mock(AiClient.class);
    private final RagSearchRepository repository = mock(RagSearchRepository.class);
    private final RerankClient reranker = mock(RerankClient.class);

    private static List<RagHit> cosine(int n) {
        return IntStream.range(0, n).mapToObj(i -> new RagHit("f" + i + ".md", null, "c" + i, 0.9 - i * 0.01)).toList();
    }

    private RagSearchService service(int candidates, int minCandidates) {
        when(repository.hasChunks(SESSION)).thenReturn(true);
        when(ai.embedWithModel(List.of(QUERY))).thenReturn(new AiClient.EmbedResponse("m", List.of(new float[1024])));
        for (int n = 1; n <= 3; n++) {
            when(repository.findNearest(eq(SESSION), eq("m"), anyString(), eq(n))).thenReturn(cosine(n));
        }
        when(reranker.isConfigured()).thenReturn(true);
        return new RagSearchService(ai, repository, reranker,
                new RagRerankProperties(true, candidates, Duration.ofSeconds(2), minCandidates));
    }

    @Test
    void belowMinCandidatesWithSmallNStillReturnsCosineTopLimit() {
        List<RagHit> result = service(1, 2).search(SESSION, QUERY, 3);

        assertThat(result).containsExactlyElementsOf(cosine(3));
        verify(reranker, never()).rerank(anyString(), anyList());
    }

    @Test
    void rerankFailureWithSmallNStillReturnsCosineTopLimit() {
        RagSearchService service = service(2, 2);
        when(reranker.rerank(anyString(), anyList())).thenThrow(new RerankUnavailableException("timeout"));

        assertThat(service.search(SESSION, QUERY, 3)).containsExactlyElementsOf(cosine(3));
        verify(repository).findNearest(eq(SESSION), eq("m"), anyString(), eq(2));
        verify(repository).findNearest(eq(SESSION), eq("m"), anyString(), eq(3));
    }

    @Test
    void largeNNeverReadsTheRepositoryTwice() {
        RagSearchService service = service(3, 2);
        when(reranker.rerank(anyString(), anyList())).thenThrow(new RerankUnavailableException("status"));

        assertThat(service.search(SESSION, QUERY, 3)).containsExactlyElementsOf(cosine(3));
        verify(repository).findNearest(eq(SESSION), eq("m"), anyString(), eq(3));
        verify(repository, never()).findNearest(eq(SESSION), eq("m"), anyString(), eq(2));
    }
}
