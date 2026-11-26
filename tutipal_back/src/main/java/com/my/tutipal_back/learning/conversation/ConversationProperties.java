package com.my.tutipal_back.learning.conversation;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * PT-106 app.ai.conversations.*: 저장 대화 보관과 요약 규칙.
 *
 * <p>보관 기간 (PT-13 PM 결정 2026-11-26). 수업 대화는 수업마다 선생님이 정한 study_sessions.ai_retention_days
 * (수업 종료 뒤 0..90일)로 지우므로 여기 설정이 없다. personalRetentionDays: 개인 대화(session_id 없음)를 마지막 활동
 * (last_message_at, 메시지가 없으면 만든 시각) 뒤 N일이 지나면 (사용자가 지운 것 포함) 물리 삭제한다. 기본 30, 1 이상.
 * "저장"한 대화(saved_at)는 두 보관 기간 모두에서 빠진다. retentionBatchSize: 한 번에 지우는 행 수
 * (보관 기간 작업과 삭제 유예 작업이 같이 쓴다).
 *
 * <p>deletedPurgeDays: 사용자가 지운 대화 · 기억(deleted_at)을 N일 뒤 물리 삭제한다. 기본 30 (저장한 대화도 같음).
 * 1 이상만 허용한다: 0 을 "즉시 물리 삭제"로 두면 삭제 경로가 둘이 되어 시작할 때 막는다.
 *
 * <p>summary: 활성 요약 뒤 메시지 중 최신 keepRecent 개를 뺀 수가 threshold 이상이면 가장 오래된 것부터
 * 최대 maxInput 개(합계 maxInputChars 자 이내)를 요약한다. slotWait: 요약이 AiSlots 자리를 기다리는 최대 시간
 * (못 얻으면 이번엔 건너뜀). maxConcurrent: 서버 전체에서 동시에 도는 요약 수 (자리가 없으면 기다리지 않고 건너뜀).
 * 코드 기본값은 2 (AiSlots 기본 자리 수), application.yaml 은 채팅 자리를 남기려고 1 로 둔다.
 */
@ConfigurationProperties("app.ai.conversations")
public record ConversationProperties(
        @DefaultValue("30") int personalRetentionDays,
        @DefaultValue("500") int retentionBatchSize,
        @DefaultValue("30") int deletedPurgeDays,
        @DefaultValue Summary summary) {

    /** 사용자가 지운 대화 · 기억의 물리 삭제 유예 일수 기본값. */
    public static final int DEFAULT_DELETED_PURGE_DAYS = 30;

    /** PT-13 결정 B: 개인 대화는 마지막 활동 30일 뒤 삭제. */
    public static final int DEFAULT_PERSONAL_RETENTION_DAYS = 30;

    /** 계약 6.5: 요약 입력은 1..40 메시지. */
    private static final int MAX_SUMMARY_INPUT = 40;

    /** 삭제 유예 기간은 기본값(30일)으로. */
    public ConversationProperties(int personalRetentionDays, int retentionBatchSize, Summary summary) {
        this(personalRetentionDays, retentionBatchSize, DEFAULT_DELETED_PURGE_DAYS, summary);
    }

    @ConstructorBinding
    public ConversationProperties {
        if (personalRetentionDays < 1) {
            throw new IllegalArgumentException("app.ai.conversations.personal-retention-days 는 1 이상이어야 해요"
                    + " (개인 대화를 마지막 활동 뒤 지우기까지의 일수)");
        }
        if (retentionBatchSize < 1) {
            throw new IllegalArgumentException("app.ai.conversations.retention-batch-size 는 1 이상이어야 해요");
        }
        if (deletedPurgeDays < 1) {
            throw new IllegalArgumentException("app.ai.conversations.deleted-purge-days 는 1 이상이어야 해요"
                    + " (지운 대화 · 기억을 물리 삭제하기 전 유예 일수, 0 = 즉시 삭제는 지원하지 않아요)");
        }
        if (summary == null) {
            summary = Summary.defaults();
        }
    }

    public record Summary(
            @DefaultValue("20") int threshold,
            @DefaultValue("6") int keepRecent,
            @DefaultValue("40") int maxInput,
            @DefaultValue("5s") Duration slotWait,
            @DefaultValue("2") int maxConcurrent,
            @DefaultValue("12000") int maxInputChars) {

        /** 계약 6.5: 요약 입력 메시지는 각 8000자까지. */
        static final int MAX_MESSAGE_LENGTH = 8000;

        public Summary(int threshold, int keepRecent, int maxInput, Duration slotWait) {
            this(threshold, keepRecent, maxInput, slotWait, 2, 12000);
        }

        @ConstructorBinding
        public Summary {
            if (threshold < 1 || keepRecent < 0 || maxInput < 1 || maxInput > MAX_SUMMARY_INPUT) {
                throw new IllegalArgumentException(
                        "app.ai.conversations.summary: threshold>=1, keep-recent>=0, max-input 1..40 이어야 해요");
            }
            if (slotWait == null || slotWait.isNegative()) {
                throw new IllegalArgumentException("app.ai.conversations.summary.slot-wait 는 0 이상이어야 해요");
            }
            if (maxConcurrent < 1 || maxInputChars < MAX_MESSAGE_LENGTH) {
                throw new IllegalArgumentException(
                        "app.ai.conversations.summary: max-concurrent>=1, max-input-chars>=8000 이어야 해요");
            }
        }

        static Summary defaults() {
            return new Summary(20, 6, 40, Duration.ofSeconds(5));
        }
    }

    public static ConversationProperties defaults() {
        return new ConversationProperties(DEFAULT_PERSONAL_RETENTION_DAYS, 500, DEFAULT_DELETED_PURGE_DAYS,
                Summary.defaults());
    }
}
