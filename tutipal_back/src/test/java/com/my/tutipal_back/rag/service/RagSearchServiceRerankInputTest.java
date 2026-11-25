package com.my.tutipal_back.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.ai.client.RerankClient;
import com.my.tutipal_back.ai.client.RerankUnavailableException;
import com.my.tutipal_back.rag.config.RagRerankProperties;
import com.my.tutipal_back.rag.dto.RagHit;
import com.my.tutipal_back.rag.repository.RagSearchRepository;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/** PT-86 review round 1: inputs stay inside the /v1/rerank contract (1000/2000 chars); fallback logs are rate-limited. */
class RagSearchServiceRerankInputTest {

    private static final long SESSION = 3L;

    private final AiClient ai = mock(AiClient.class);
    private final RagSearchRepository repository = mock(RagSearchRepository.class);
    private final RerankClient reranker = mock(RerankClient.class);
    private final Logger logger = (Logger) LoggerFactory.getLogger("com.my.tutipal_back.materials.service.MaterialService");
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @AfterEach
    void detach() {
        logger.detachAppender(logs);
    }

    private RagSearchService service(String query, List<RagHit> candidates) {
        when(repository.hasChunks(SESSION)).thenReturn(true);
        when(ai.embedWithModel(List.of(query.strip()))).thenReturn(new AiClient.EmbedResponse("m", List.of(new float[1024])));
        when(repository.findNearest(eq(SESSION), eq("m"), anyString(), eq(10))).thenReturn(candidates);
        when(reranker.isConfigured()).thenReturn(true);
        return new RagSearchService(ai, repository, reranker, new RagRerankProperties(true, 10, Duration.ofSeconds(2), 2));
    }

    @Test
    void longQueryAndDocumentsAreTruncatedToContractLimits() {
        String query = "질".repeat(1200);
        List<RagHit> candidates = List.of(new RagHit("a.md", null, "가".repeat(2500), 0.9),
                new RagHit("b.md", null, "짧은 조각", 0.8));
        when(reranker.rerank(anyString(), anyList())).thenReturn(new double[] {0.1, 0.9});

        List<RagHit> result = service(query, candidates).search(SESSION, query, 3);

        ArgumentCaptor<String> sentQuery = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> sentDocs = ArgumentCaptor.forClass(List.class);
        verify(reranker).rerank(sentQuery.capture(), sentDocs.capture());
        assertThat(sentQuery.getValue()).hasSize(1000);
        assertThat(sentDocs.getValue().get(0)).hasSize(2000);
        assertThat(sentDocs.getValue().get(1)).isEqualTo("짧은 조각");
        // the returned hit keeps its full content
        assertThat(result.get(1).content()).hasSize(2500);
    }

    @Test
    void truncationDoesNotSplitASurrogatePair() {
        String doc = "가".repeat(1999) + "😀" + "끝";
        List<RagHit> candidates = List.of(new RagHit("a.md", null, doc, 0.9), new RagHit("b.md", null, "다른 조각", 0.8));
        when(reranker.rerank(anyString(), anyList())).thenReturn(new double[] {0.1, 0.9});

        service("q", candidates).search(SESSION, "q", 3);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> sentDocs = ArgumentCaptor.forClass(List.class);
        verify(reranker).rerank(anyString(), sentDocs.capture());
        assertThat(sentDocs.getValue().get(0)).hasSize(1999).doesNotContain("\uD83D");
    }

    @Test
    void repeatedFallbackWithSameReasonIsLoggedOncePerMinute() {
        logs.start();
        logger.addAppender(logs);
        RagSearchService service = service("q", List.of(new RagHit("a.md", null, "조각 하나", 0.9),
                new RagHit("b.md", null, "조각 둘", 0.8)));
        when(reranker.rerank(anyString(), anyList())).thenThrow(new RerankUnavailableException("status"));

        for (int i = 0; i < 5; i++) {
            assertThat(service.search(SESSION, "q", 3)).hasSize(2);
        }

        assertThat(logs.list).filteredOn(e -> e.getFormattedMessage().contains("rerank fallback: status")).hasSize(1);
    }
}
