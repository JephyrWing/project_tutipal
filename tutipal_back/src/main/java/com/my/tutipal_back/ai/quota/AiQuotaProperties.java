package com.my.tutipal_back.ai.quota;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * PT-97: 기능별 AI 호출 한도 (app.ai.quota). 기능 하나라도 빠지면 시작 단계에서 실패한다.
 * cleanupInterval 은 만료된 기록을 지우는 주기. @Scheduled 도 기본값 없이 같은 키를 읽으므로 설정은 application.yaml 한 곳.
 */
@Validated
@ConfigurationProperties("app.ai.quota")
public record AiQuotaProperties(
        Limit screenAuto,
        Limit screenManual,
        Limit chat,
        Limit quizGenerate,
        Limit insights,
        Duration cleanupInterval) {

    public AiQuotaProperties {
        require(screenAuto, "screen-auto");
        require(screenManual, "screen-manual");
        require(chat, "chat");
        require(quizGenerate, "quiz-generate");
        require(insights, "insights");
        if (cleanupInterval == null || cleanupInterval.isZero() || cleanupInterval.isNegative()) {
            throw new IllegalArgumentException("app.ai.quota.cleanup-interval 은 0보다 커야 해요");
        }
    }

    public Limit of(AiFeature feature) {
        return switch (feature) {
            case SCREEN_AUTO -> screenAuto;
            case SCREEN_MANUAL -> screenManual;
            case CHAT -> chat;
            case QUIZ_GENERATE -> quizGenerate;
            case INSIGHTS -> insights;
        };
    }

    private static void require(Limit limit, String key) {
        if (limit == null) {
            throw new IllegalArgumentException("app.ai.quota." + key + " 설정이 없어요");
        }
    }

    /** window 동안 limit 번까지 허용 (슬라이딩 윈도). */
    public record Limit(int limit, Duration window) {

        public Limit {
            if (limit < 1) {
                throw new IllegalArgumentException("AI 호출 한도(limit)는 1 이상이어야 해요");
            }
            if (window == null || window.isZero() || window.isNegative()) {
                throw new IllegalArgumentException("AI 호출 한도 기간(window)은 0보다 커야 해요");
            }
        }
    }
}
