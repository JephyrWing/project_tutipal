package com.my.tutipal_back.rag.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PT-86: 검색 후보 재순위화 (tutipal.rag.rerank). 기본은 꺼짐이며, 꺼져 있으면 기존 코사인 검색과 똑같이 동작한다.
 *
 * @param enabled       재순위화 사용 여부
 * @param candidates    켜졌을 때 가져올 후보 수. FastAPI 요청 상한(32)에 맞춰 1..32 로 보정한다
 * @param timeout       재순위화 요청 전체 읽기 제한 (연결 제한은 1초 고정)
 * @param minCandidates 0.5 필터 뒤 후보가 이보다 적으면 재순위화하지 않고 코사인 순서를 쓴다
 */
@ConfigurationProperties("tutipal.rag.rerank")
public record RagRerankProperties(boolean enabled, int candidates, Duration timeout, int minCandidates) {

    public static final int MAX_CANDIDATES = 32;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);

    public RagRerankProperties {
        candidates = Math.clamp(candidates, 1, MAX_CANDIDATES);
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = DEFAULT_TIMEOUT;
        }
        minCandidates = Math.max(1, minCandidates);
    }
}
