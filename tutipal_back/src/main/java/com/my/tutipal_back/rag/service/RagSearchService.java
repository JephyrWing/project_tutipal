package com.my.tutipal_back.rag.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.rag.dto.RagHit;
import com.my.tutipal_back.rag.repository.RagSearchRepository;

@Service
@Transactional(readOnly = true)
public class RagSearchService {

    // 검색 실패 로그의 기존 로거 이름을 유지한다.
    private static final Logger log = LoggerFactory.getLogger("com.my.tutipal_back.materials.service.MaterialService");
    private static final double MIN_SIMILARITY = 0.5;

    private final AiClient ai;
    private final RagSearchRepository repository;

    public RagSearchService(AiClient ai, RagSearchRepository repository) {
        this.ai = ai;
        this.repository = repository;
    }

    /**
     * 세션 참가자 확인은 호출자가 한다. 관련 자료를 찾지 못하거나 임베딩·검색에 실패하면 빈 목록을 반환한다.
     */
    public List<RagHit> search(Long sessionId, String query, int limit) {
        if (!repository.hasChunks(sessionId) || query == null || query.isBlank()) {
            return List.of();
        }
        try {
            AiClient.EmbedResponse embedded = ai.embedWithModel(List.of(query.strip()));
            String vector = AiClient.pgVector(embedded.vectors().getFirst());
            return repository.findNearest(sessionId, embedded.model(), vector, limit).stream()
                    .filter(hit -> hit.similarity() >= MIN_SIMILARITY)
                    .toList();
        } catch (RuntimeException e) {
            log.warn("material search failed: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }
}
