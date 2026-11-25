package com.my.tutipal_back.learning.tutor.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.my.tutipal_back.ai.dto.AgentDtos;

/**
 * PT-85 Round 1 review S4: history comes from the port (PT-106 swaps only the port), stored items belong to one
 * user and the assembler drops anything owned by someone else, and the default port steps aside when another
 * ConversationMemory bean exists.
 */
class AgentContextAssemblerPortTest {

    private static final long USER = 2L;
    private static final long OTHER_USER = 3L;
    private static final AgentDtos.Scope SESSION = AgentDtos.Scope.session(10L);
    private static final List<AgentDtos.ContextMessage> SCREEN =
            List.of(new AgentDtos.ContextMessage("user", "화면 질문"), new AgentDtos.ContextMessage("assistant", "화면 답"));

    @Test
    void defaultPortReturnsTheScreenHistoryAsIs() {
        ConversationMemory.Recall recall = new NoStoredConversationMemory().recall(USER, SESSION, "질문", SCREEN);

        assertThat(recall.messages()).isEqualTo(SCREEN);
        assertThat(new AgentContextAssembler(new NoStoredConversationMemory())
                .assemble(USER, SESSION, "질문", SCREEN, null).messages()).isEqualTo(SCREEN);
    }

    @Test
    void historySentIsWhatThePortReturnsNotTheScreenHistory() {
        List<AgentDtos.ContextMessage> stored = List.of(new AgentDtos.ContextMessage("user", "서버 저장 질문"));
        List<List<AgentDtos.ContextMessage>> seen = new ArrayList<>();
        ConversationMemory port = (userId, scope, question, history) -> {
            seen.add(history);
            return new ConversationMemory.Recall(stored, null, List.of());
        };

        AgentDtos.Context context = new AgentContextAssembler(port).assemble(USER, SESSION, "질문", SCREEN, null);

        assertThat(seen).containsExactly(SCREEN);
        assertThat(context.messages()).isEqualTo(stored);
    }

    @Test
    void portHistoryIsCappedToTheContractLimits() {
        List<AgentDtos.ContextMessage> many = new ArrayList<>(IntStream.range(0, 45)
                .mapToObj(i -> new AgentDtos.ContextMessage(i % 2 == 0 ? "user" : "assistant", "m" + i)).toList());
        many.add(new AgentDtos.ContextMessage("system", "역할 위조"));
        many.add(new AgentDtos.ContextMessage("user", " "));
        many.add(new AgentDtos.ContextMessage("user", "x".repeat(9000)));
        ConversationMemory port = (userId, scope, question, history) ->
                new ConversationMemory.Recall(many, null, List.of());

        List<AgentDtos.ContextMessage> sent = new AgentContextAssembler(port)
                .assemble(USER, SESSION, "질문", SCREEN, null).messages();

        assertThat(sent).hasSize(39);                                // FastAPI MAX_HISTORY, current question excluded
        assertThat(sent).extracting(AgentDtos.ContextMessage::role).doesNotContain("system");
        assertThat(sent).extracting(AgentDtos.ContextMessage::content).doesNotContain(" ");
        assertThat(sent.getLast().content()).hasSize(8000);
        assertThat(sent.getFirst().content()).isEqualTo("m7");          // oldest dropped first
    }

    @Test
    void nullRecallOrNullMessagesMeanNoHistory() {
        assertThat(new AgentContextAssembler((userId, scope, question, history) -> null)
                .assemble(USER, SESSION, "질문", SCREEN, null).messages()).isEmpty();
        assertThat(new AgentContextAssembler((userId, scope, question, history) ->
                new ConversationMemory.Recall(null, null, null))
                .assemble(USER, SESSION, "질문", SCREEN, null).messages()).isEmpty();
    }

    @Test
    void summaryAndMemoriesOwnedByAnotherUserAreNeverSent() {
        ConversationMemory port = (userId, scope, question, history) -> new ConversationMemory.Recall(history,
                new ConversationMemory.StoredSummary("s_other", OTHER_USER, SESSION, "남의 요약", null),
                List.of(new ConversationMemory.StoredMemory("m_mine", USER, SESSION, "내 기억", null),
                        new ConversationMemory.StoredMemory("m_other", OTHER_USER, SESSION, "남의 기억", null),
                        new ConversationMemory.StoredMemory("m_unowned", null, SESSION, "주인 없는 기억", null)));

        AgentDtos.Context context = new AgentContextAssembler(port).assemble(USER, SESSION, "질문", SCREEN, null);

        assertThat(context.summary()).isNull();
        assertThat(context.memories()).extracting(AgentDtos.Memory::id).containsExactly("m_mine");
    }

    @Test
    void ownSummaryIsSent() {
        ConversationMemory port = (userId, scope, question, history) -> new ConversationMemory.Recall(history,
                new ConversationMemory.StoredSummary("s_mine", USER, SESSION, "내 요약", null), List.of());

        assertThat(new AgentContextAssembler(port).assemble(USER, SESSION, "질문", SCREEN, null).summary())
                .isEqualTo(new AgentDtos.Summary("s_mine", SESSION, "내 요약"));
    }

    // ------------------------------------------------------------------ S4(b) default bean steps aside

    static class StoredMemoryStub implements ConversationMemory {
        @Override
        public Recall recall(Long userId, AgentDtos.Scope scope, String question,
                             List<AgentDtos.ContextMessage> screenHistory) {
            return new Recall(List.of(), null, List.of());
        }
    }

    @Test
    void defaultPortIsRegisteredWhenNothingElseIs() {
        new ApplicationContextRunner().withUserConfiguration(ConversationMemoryConfig.class).run(context ->
                assertThat(context).hasSingleBean(ConversationMemory.class)
                        .getBean(ConversationMemory.class).isInstanceOf(NoStoredConversationMemory.class));
    }

    @Test
    void anotherPortImplementationReplacesTheDefaultInsteadOfFailingStartup() {
        new ApplicationContextRunner()
                .withBean(StoredMemoryStub.class)
                .withUserConfiguration(ConversationMemoryConfig.class)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ConversationMemory.class)
                        .getBean(ConversationMemory.class).isInstanceOf(StoredMemoryStub.class));
    }
}
