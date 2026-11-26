package com.my.tutipal_back.learning.insights.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * PT-87 Codex (merge review): leave / kick racing with a question INSERT. The SQL must lock the participant row:
 * FOR SHARE in the INSERT, FOR UPDATE before the delete. The real two-connection race is in
 * ConceptInsightsPostgresTest (insightsDbTest).
 */
class ChatQuestionStoreLockingTest {

    /** Records SQL instead of talking to a database. */
    private static final class RecordingJdbc extends JdbcTemplate {
        private final List<String> sql = new ArrayList<>();

        @Override
        public int update(String statement, Object... args) {
            sql.add(statement);
            return 1;
        }

        @Override
        public <T> List<T> query(String statement, RowMapper<T> rowMapper, Object... args) {
            sql.add(statement);
            return List.of();
        }
    }

    private final RecordingJdbc jdbc = new RecordingJdbc();
    private final ChatQuestionStore store = new ChatQuestionStore(jdbc);

    @Test
    void insertLocksTheParticipantRowForShare() {
        store.insertIfActiveStudent(10L, 21L, "합성 질문입니다");

        assertThat(jdbc.sql).hasSize(1);
        assertThat(jdbc.sql.getFirst()).containsIgnoringCase("FOR SHARE OF p")
                .doesNotContainIgnoringCase("FOR UPDATE");
    }

    @Test
    void deleteLocksTheParticipantRowForUpdateBeforeDeletingQuestions() {
        store.deleteBySessionAndUser(10L, 21L);

        assertThat(jdbc.sql).hasSize(2);
        assertThat(jdbc.sql.get(0)).containsIgnoringCase("FROM session_participants")
                .containsIgnoringCase("FOR UPDATE");
        assertThat(jdbc.sql.get(1)).startsWith("DELETE FROM chat_questions");
    }
}
