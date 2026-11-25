package com.my.tutipal_back.learning.conversation;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * PT-106 app.ai.conversations.*: 저장 대화 보관과 요약 규칙.
 *
 * <p>retentionDays: 마지막 메시지 뒤 N일 지난 대화를 지운다. 0 = 끔(기본). 보관 기간은 PT-13 미정이라
 * 사용자가 지우거나 탈퇴할 때까지 남긴다. retentionBatchSize: 한 번에 지우는 대화 수.
 *
 * <p>summary: 활성 요약 뒤 메시지 중 최신 keepRecent 개를 뺀 수가 threshold 이상이면 가장 오래된 것부터
 * 최대 maxInput 개를 요약한다. slotWait: 요약이 AiSlots 자리를 기다리는 최대 시간 (못 얻으면 이번엔 건너뜀).
 */
@ConfigurationProperties("app.ai.conversations")
public record ConversationProperties(
        @DefaultValue("0") int retentionDays,
        @DefaultValue("500") int retentionBatchSize,
        @DefaultValue Summary summary) {

    /** 계약 6.5: 요약 입력은 1..40 메시지. */
    private static final int MAX_SUMMARY_INPUT = 40;

    public ConversationProperties {
        if (retentionDays < 0) {
            throw new IllegalArgumentException("app.ai.conversations.retention-days 는 0 이상이어야 해요");
        }
        if (retentionBatchSize < 1) {
            throw new IllegalArgumentException("app.ai.conversations.retention-batch-size 는 1 이상이어야 해요");
        }
        if (summary == null) {
            summary = Summary.defaults();
        }
    }

    public record Summary(
            @DefaultValue("20") int threshold,
            @DefaultValue("6") int keepRecent,
            @DefaultValue("40") int maxInput,
            @DefaultValue("5s") Duration slotWait) {

        public Summary {
            if (threshold < 1 || keepRecent < 0 || maxInput < 1 || maxInput > MAX_SUMMARY_INPUT) {
                throw new IllegalArgumentException(
                        "app.ai.conversations.summary: threshold>=1, keep-recent>=0, max-input 1..40 이어야 해요");
            }
            if (slotWait == null || slotWait.isNegative()) {
                throw new IllegalArgumentException("app.ai.conversations.summary.slot-wait 는 0 이상이어야 해요");
            }
        }

        static Summary defaults() {
            return new Summary(20, 6, 40, Duration.ofSeconds(5));
        }
    }

    public static ConversationProperties defaults() {
        return new ConversationProperties(0, 500, Summary.defaults());
    }
}
