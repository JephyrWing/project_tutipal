package com.my.tutipal_back.sessions.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** PT-13 결정 A: 선생님이 수업 AI 대화 보관 일수를 바꾼다 (수업 종료 뒤 0..90일). */
public record UpdateAiRetentionRequest(
        @NotNull(message = "AI 대화 보관 기간을 정해 주세요")
        @Min(value = 0, message = "AI 대화 보관 기간은 0~90일이에요")
        @Max(value = 90, message = "AI 대화 보관 기간은 0~90일이에요") Integer aiRetentionDays) {
}
