package com.my.tutipal_back.learning.tutor.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.my.tutipal_back.ai.dto.AgentDtos;

/** PT-85 AC-26: one log line per agent turn, made only of labels and counts. */
class AgentTurnLogTest {

    static AgentDtos.Meta meta(String route, Integer hint, boolean help, String variant) {
        return new AgentDtos.Meta("student", route, List.of("classify", "hint", "compose", "respond"), 1, hint, help, 1,
                variant, false, new AgentDtos.ContextReport(800, 3000,
                        Map.of("messages", 0, "memories", 0, "materials", 0, "screenSummary", 0), List.of()),
                List.of("m_1"), null);
    }

    @Test
    void lineHasEveryFieldNamedInTheContract() {
        String line = AgentTurnLog.format("student", meta("answer_request", 2, true, null),
                List.of("answer_question", "material_search"), 1, 1234L);

        assertThat(line).contains("kind=student", "intent=answer_request", "hint=2", "help=true", "searchRounds=1",
                "variant=", "elapsedMs=1234", "tools=", "answer_question", "material_search", "path=");
        assertThat(line).contains("classify", "hint", "compose", "respond");
        assertThat(line).doesNotContain("\n");
    }

    @Test
    void variantTurnReportsVariantResult() {
        String line = AgentTurnLog.format("student", meta("variant", null, false, "failed"),
                List.of("answer_question", "question_variant"), 0, 10L);

        assertThat(line).contains("intent=variant", "variant=failed", "searchRounds=0");
    }
}
