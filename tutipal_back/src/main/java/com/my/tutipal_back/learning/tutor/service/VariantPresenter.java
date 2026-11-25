package com.my.tutipal_back.learning.tutor.service;

import java.util.List;

import com.my.tutipal_back.ai.dto.AgentDtos;

/**
 * PT-85 AC-18 · 계약 7.7: 변형 문항에 수업 정답 공개 정책(hideAnswers)을 적용한다.
 * 숨김이면 정답 · 모범 답안 · 해설을 아예 읽지 않으므로 SSE 어디에도 나갈 수 없다.
 */
final class VariantPresenter {

    private VariantPresenter() {
    }

    /** 학생에게 delta 로 보낼 글. 숨김이 아니면 문제 뒤에 정답 · 해설 단락을 붙인다. */
    static String render(AgentDtos.VariantQuestion variant, boolean hideAnswers) {
        StringBuilder sb = new StringBuilder("비슷한 문제를 만들어 봤어요.\n\n").append(variant.question());
        List<String> choices = variant.choices();
        if (choices != null) {
            for (int i = 0; i < choices.size(); i++) {
                sb.append('\n').append(i + 1).append(") ").append(choices.get(i));
            }
        }
        if (hideAnswers) {
            return sb.toString();
        }
        Integer answer = variant.answerIndex();
        if (choices != null && answer != null && answer >= 0 && answer < choices.size()) {
            sb.append("\n\n정답: ").append(answer + 1).append("번 (").append(choices.get(answer)).append(')');
        } else if (variant.modelAnswer() != null && !variant.modelAnswer().isBlank()) {
            sb.append("\n\n모범 답안: ").append(variant.modelAnswer());
        }
        if (variant.explanation() != null && !variant.explanation().isBlank()) {
            sb.append("\n해설: ").append(variant.explanation());
        }
        return sb.toString();
    }

    /** SSE agent 이벤트에 싣는 사본. 숨김이면 type · question · choices 만 남긴다. */
    static AgentDtos.VariantQuestion visible(AgentDtos.VariantQuestion variant, boolean hideAnswers) {
        if (!hideAnswers) {
            return variant;
        }
        return new AgentDtos.VariantQuestion(variant.type(), variant.question(), variant.choices(), null, null, null);
    }
}
