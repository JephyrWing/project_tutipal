package com.my.tutipal_back.learning.tutor.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 도우미 챗봇 요청. 대화 기록은 화면이 들고 와서 함께 보낸다.
 * sessionId 가 있으면 topic 대신 그 세션 선생님 설정을 따른다 (참가자만).
 * variantQuizId: 수업 학생이 자기 오답 문항의 변형을 요청할 때 (PT-85, sessionId 필수).
 * screenSummary: 선택 창 분석 요약 (PT-85 개인 학습 등, 없으면 null).
 * ponytail: 대화는 저장하지 않음, 복습 · 기록 기능이 필요해지면 서버 저장
 */
public record ChatRequest(
        @NotBlank @Size(max = 30) String topic,
        @NotEmpty @Size(max = 40) List<@Valid Message> messages,
        Long sessionId,
        Long variantQuizId,
        @Size(max = 4000) String screenSummary) {

    public ChatRequest(String topic, List<Message> messages, Long sessionId) {
        this(topic, messages, sessionId, null, null);
    }

    public record Message(
            @NotBlank @Pattern(regexp = "user|assistant") String role,
            @NotBlank @Size(max = 8000) String content) {
    }
}
