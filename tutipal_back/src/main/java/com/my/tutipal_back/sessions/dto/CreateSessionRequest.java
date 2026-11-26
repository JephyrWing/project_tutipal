package com.my.tutipal_back.sessions.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * aiRetentionDays: PT-13 결정 A. 수업 AI 대화를 수업 종료 뒤 며칠 보관할지 (0..90, 0 = 종료 직후 삭제). 기본값 없이 필수.
 */
public record CreateSessionRequest(
        @NotBlank @Size(max = 50) String title,
        @NotBlank @Size(max = 30) String topic,
        @NotNull(message = "AI 대화 보관 기간을 정해 주세요")
        @Min(value = 0, message = "AI 대화 보관 기간은 0~90일이에요")
        @Max(value = 90, message = "AI 대화 보관 기간은 0~90일이에요") Integer aiRetentionDays) {
}
