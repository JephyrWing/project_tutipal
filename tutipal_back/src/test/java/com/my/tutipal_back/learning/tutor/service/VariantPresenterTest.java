package com.my.tutipal_back.learning.tutor.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.my.tutipal_back.ai.dto.AgentDtos;

/** PT-85 AC-18 / AC-19, contract 7.7: the class answer policy is applied by Spring before anything reaches SSE. */
class VariantPresenterTest {

    private static final AgentDtos.VariantQuestion CHOICE = new AgentDtos.VariantQuestion("CHOICE",
            "시속 24 km로 30분 동안 달리면 몇 km?", List.of("8 km", "12 km", "24 km"), 1, null,
            "EXPLANATION-SECRET 30분은 0.5시간");
    private static final AgentDtos.VariantQuestion TEXT = new AgentDtos.VariantQuestion("TEXT",
            "작년보다 20% 늘어 144명이면 작년은?", null, null, "MODEL-ANSWER-SECRET 120명",
            "EXPLANATION-SECRET 144 / 1.2");

    @Test
    void hiddenChoiceShowsQuestionAndChoicesOnly() {
        String text = VariantPresenter.render(CHOICE, true);

        assertThat(text).contains(CHOICE.question(), "8 km", "12 km", "24 km");
        assertThat(text).doesNotContain("EXPLANATION-SECRET");
    }

    @Test
    void hiddenRenderDoesNotDependOnAnswerFields() {
        AgentDtos.VariantQuestion otherAnswer = new AgentDtos.VariantQuestion("CHOICE", CHOICE.question(),
                CHOICE.choices(), 2, null, "다른 해설");

        assertThat(VariantPresenter.render(otherAnswer, true)).isEqualTo(VariantPresenter.render(CHOICE, true));
    }

    @Test
    void hiddenTextShowsNoModelAnswerOrExplanation() {
        String text = VariantPresenter.render(TEXT, true);

        assertThat(text).contains(TEXT.question());
        assertThat(text).doesNotContain("MODEL-ANSWER-SECRET", "EXPLANATION-SECRET");
    }

    @Test
    void shownChoiceAppendsAnswerAndExplanationAfterQuestion() {
        String text = VariantPresenter.render(CHOICE, false);

        assertThat(text).contains(CHOICE.question(), "EXPLANATION-SECRET");
        assertThat(text.indexOf("EXPLANATION-SECRET")).isGreaterThan(text.indexOf(CHOICE.question()));
        String afterChoices = text.substring(text.indexOf("24 km") + "24 km".length());
        assertThat(afterChoices.contains("12 km") || afterChoices.contains("2번")).isTrue();
    }

    @Test
    void shownTextAppendsModelAnswerAndExplanation() {
        String text = VariantPresenter.render(TEXT, false);

        assertThat(text).contains("MODEL-ANSWER-SECRET", "EXPLANATION-SECRET");
        assertThat(text.indexOf("MODEL-ANSWER-SECRET")).isGreaterThan(text.indexOf(TEXT.question()));
    }

    @Test
    void visibleCopyDropsAnswerFieldsWhenHidden() {
        AgentDtos.VariantQuestion visible = VariantPresenter.visible(CHOICE, true);

        assertThat(visible.type()).isEqualTo("CHOICE");
        assertThat(visible.question()).isEqualTo(CHOICE.question());
        assertThat(visible.choices()).isEqualTo(CHOICE.choices());
        assertThat(visible.answerIndex()).isNull();
        assertThat(visible.modelAnswer()).isNull();
        assertThat(visible.explanation()).isNull();
        assertThat(VariantPresenter.visible(TEXT, true).modelAnswer()).isNull();
    }

    @Test
    void visibleCopyKeepsEverythingWhenShown() {
        assertThat(VariantPresenter.visible(CHOICE, false)).isEqualTo(CHOICE);
        assertThat(VariantPresenter.visible(TEXT, false)).isEqualTo(TEXT);
    }
}
