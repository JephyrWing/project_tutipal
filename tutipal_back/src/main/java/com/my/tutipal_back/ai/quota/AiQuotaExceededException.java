package com.my.tutipal_back.ai.quota;

import java.time.Instant;

/**
 * PT-97: 사용자 × 기능 한도를 다 씀. GlobalExceptionHandler 가 429 + Retry-After + ProblemDetail 로 응답한다.
 * ApiException 을 상속하지 않는다 (TutorController 의 지역 ApiException 처리기가 {status, message} 로 바꾸지 않게).
 * 메시지에는 시각 대신 남은 분만 넣는다: 서버 시간대와 사용자 시간대가 다를 수 있어 HH:mm 은 화면이 retryAt 으로 만든다.
 */
public class AiQuotaExceededException extends RuntimeException {

    private final AiFeature feature;
    private final Instant retryAt;
    private final long retryAfterSeconds;

    public AiQuotaExceededException(AiFeature feature, Instant retryAt, long retryAfterSeconds) {
        super(message(feature, retryAfterSeconds));
        this.feature = feature;
        this.retryAt = retryAt;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public AiFeature getFeature() {
        return feature;
    }

    public Instant getRetryAt() {
        return retryAt;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    private static String message(AiFeature feature, long retryAfterSeconds) {
        long minutes = Math.max(1, (retryAfterSeconds + 59) / 60);
        return feature.label() + " 한도를 다 썼어요. 약 " + minutes + "분 뒤에 다시 시도해 주세요";
    }
}
