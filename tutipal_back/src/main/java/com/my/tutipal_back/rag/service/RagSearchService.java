package com.my.tutipal_back.rag.service;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.ai.client.RerankClient;
import com.my.tutipal_back.ai.client.RerankUnavailableException;
import com.my.tutipal_back.rag.config.RagRerankProperties;
import com.my.tutipal_back.rag.dto.RagHit;
import com.my.tutipal_back.rag.repository.RagSearchRepository;

/**
 * 자료 RAG 검색. 기본은 pgvector 코사인 상위 limit 개 (유사도 0.5 이상).
 * PT-86: tutipal.rag.rerank.enabled 이면 후보 N개를 FastAPI cross-encoder 로 다시 줄 세운 뒤 상위 limit 개.
 * 재순위화는 순서만 바꾸며, 실패하면 코사인 순서로 돌아간다. RagHit.similarity 는 언제나 코사인 값이다.
 * <p>
 * 일부러 @Transactional 을 걸지 않는다: 재순위화 HTTP 를 기다리는 동안 DB 연결을 잡고 있지 않도록.
 * 저장소 호출(hasChunks · findNearest)은 서로 독립된 단일 읽기라 트랜잭션으로 묶을 필요가 없다.
 */
@Service
public class RagSearchService {

    // 검색 실패 로그의 기존 로거 이름을 유지한다.
    private static final Logger log = LoggerFactory.getLogger("com.my.tutipal_back.materials.service.MaterialService");
    private static final double MIN_SIMILARITY = 0.5;
    /** FastAPI /v1/rerank 입력 상한 (docs/AI_WORKFLOW_CONTRACT.md 3.1). 넘는 부분은 모델도 어차피 잘라 낸다. */
    private static final int MAX_QUERY_CHARS = 1000;
    private static final int MAX_DOCUMENT_CHARS = 2000;
    private static final long FALLBACK_LOG_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();

    private final AiClient ai;
    private final RagSearchRepository repository;
    private final RerankClient reranker;
    private final RagRerankProperties rerank;
    /** 사유별 {마지막 기록 시각(ns), 그 뒤 생략한 횟수}. Spring만 켜고 FastAPI가 꺼져 있으면 채팅마다 503이라 로그를 묶는다. */
    private final Map<String, long[]> fallbackLog = new ConcurrentHashMap<>();

    public RagSearchService(AiClient ai, RagSearchRepository repository, RerankClient reranker,
                            RagRerankProperties rerank) {
        this.ai = ai;
        this.repository = repository;
        this.reranker = reranker;
        this.rerank = rerank;
    }

    /**
     * 세션 참가자 확인은 호출자가 한다. 관련 자료를 찾지 못하거나 임베딩·검색에 실패하면 빈 목록을 반환한다.
     * 결과는 limit 개를 넘지 않고, 항상 같은 세션·같은 임베딩 모델 후보의 부분집합이다.
     */
    public List<RagHit> search(Long sessionId, String query, int limit) {
        if (!repository.hasChunks(sessionId) || query == null || query.isBlank()) {
            return List.of();
        }
        String question = query.strip();
        try {
            AiClient.EmbedResponse embedded = ai.embedWithModel(List.of(question));
            String vector = AiClient.pgVector(embedded.vectors().getFirst());
            if (!rerank.enabled()) {
                return relevant(repository.findNearest(sessionId, embedded.model(), vector, limit));
            }
            List<RagHit> candidates = relevant(
                    repository.findNearest(sessionId, embedded.model(), vector, rerank.candidates()));
            if (candidates.isEmpty()) {
                return List.of();   // 근거 없음 → 호출자가 일반 답변으로 전환
            }
            Supplier<List<RagHit>> cosine = () -> cosineTop(candidates, limit, sessionId, embedded.model(), vector);
            if (candidates.size() < rerank.minCandidates()) {
                return cosine.get();
            }
            if (!reranker.isConfigured()) {
                fallback("unconfigured");
                return cosine.get();
            }
            return reranked(question, candidates, limit, cosine);
        } catch (RuntimeException e) {
            log.warn("material search failed: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    private static List<RagHit> relevant(List<RagHit> hits) {
        return hits.stream().filter(hit -> hit.similarity() >= MIN_SIMILARITY).toList();
    }

    private static List<RagHit> top(List<RagHit> hits, int limit) {
        return hits.stream().limit(Math.max(0, limit)).toList();
    }

    /** 실패 사유는 코드·예외 종류만 남긴다. 질문과 자료 조각은 로그에 쓰지 않는다. */
    /**
     * 코사인 순서 상위 limit (재순위화를 건너뛰거나 실패했을 때). 후보 수 N이 limit보다 작게 설정됐으면(1~2) 후보만으로는
     * 꺼진 경로보다 출처가 적어지므로 그때만 limit 개로 한 번 더 읽는다 (같은 세션·같은 모델 조건).
     */
    private List<RagHit> cosineTop(List<RagHit> candidates, int limit, Long sessionId, String model, String vector) {
        if (rerank.candidates() >= limit) {
            return top(candidates, limit);
        }
        return top(relevant(repository.findNearest(sessionId, model, vector, limit)), limit);
    }

    private List<RagHit> reranked(String question, List<RagHit> candidates, int limit,
                                  Supplier<List<RagHit>> cosine) {
        double[] scores;
        try {
            scores = reranker.rerank(truncate(question, MAX_QUERY_CHARS),
                    candidates.stream().map(hit -> truncate(hit.content(), MAX_DOCUMENT_CHARS)).toList());
        } catch (RerankUnavailableException e) {
            fallback(e.reason());
            return cosine.get();
        } catch (RuntimeException e) {
            fallback(e.getClass().getSimpleName());
            return cosine.get();
        }
        if (scores == null || scores.length != candidates.size()) {
            fallback("size");
            return cosine.get();
        }
        for (double score : scores) {
            if (!Double.isFinite(score)) {
                fallback("nonfinite");
                return cosine.get();
            }
        }
        // sorted() 는 안정 정렬이라 같은 점수는 코사인 순서를 유지한다.
        return IntStream.range(0, candidates.size()).boxed()
                .sorted(Comparator.comparingDouble((Integer i) -> scores[i]).reversed())
                .limit(Math.max(0, limit))
                .map(candidates::get)
                .toList();
    }

    /** 코사인 전환 로그: 사유 코드만, 사유마다 1분에 한 번 (그 사이 생략한 횟수를 함께 남김). */
    private void fallback(String reason) {
        long now = System.nanoTime();
        long[] state = fallbackLog.computeIfAbsent(reason, r -> new long[] {now - FALLBACK_LOG_INTERVAL_NANOS, 0});
        long suppressed;
        synchronized (state) {
            if (now - state[0] < FALLBACK_LOG_INTERVAL_NANOS) {
                state[1]++;
                return;
            }
            suppressed = state[1];
            state[0] = now;
            state[1] = 0;
        }
        log.warn("material rerank fallback: {} (suppressed {} since last log)", reason, suppressed);
    }

    private static String truncate(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
        return text.substring(0, end);
    }
}
