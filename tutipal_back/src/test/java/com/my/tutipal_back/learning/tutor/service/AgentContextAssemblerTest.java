package com.my.tutipal_back.learning.tutor.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.my.tutipal_back.ai.dto.AgentDtos;

/** PT-85 AC-11 / AC-23, contract 6.6-6.7: what the assembler may send, asked again on every turn. */
class AgentContextAssemblerTest {

    private static final long USER = 2L;
    private static final AgentDtos.Scope SESSION = AgentDtos.Scope.session(10L);
    private static final AgentDtos.Scope OTHER_SESSION = AgentDtos.Scope.session(11L);
    private static final AgentDtos.Scope PERSONAL = AgentDtos.Scope.personal();
    private static final List<AgentDtos.ContextMessage> HISTORY =
            List.of(new AgentDtos.ContextMessage("user", "이전 질문"), new AgentDtos.ContextMessage("assistant", "이전 답"));

    private static ConversationMemory.StoredMemory memory(String id, AgentDtos.Scope scope, Instant deletedAt) {
        return new ConversationMemory.StoredMemory(id, USER, scope, "기억 " + id, deletedAt);
    }

    @Test
    void sendsHistoryValidSummaryAndSameScopeMemoriesInOrder() {
        ConversationMemory port = (userId, scope, question, history) -> new ConversationMemory.Recall(history, 
                new ConversationMemory.StoredSummary("s_1", USER, SESSION, "요약", null),
                List.of(memory("m_1", SESSION, null), memory("m_2", SESSION, null)));

        AgentDtos.Context context = new AgentContextAssembler(port).assemble(USER, SESSION, "질문", HISTORY, "화면");

        assertThat(context.scope()).isEqualTo(SESSION);
        assertThat(context.messages()).isEqualTo(HISTORY);
        assertThat(context.summary()).isEqualTo(new AgentDtos.Summary("s_1", SESSION, "요약"));
        assertThat(context.memories()).containsExactly(
                new AgentDtos.Memory("m_1", SESSION, "기억 m_1"), new AgentDtos.Memory("m_2", SESSION, "기억 m_2"));
        assertThat(context.screenSummary()).isEqualTo("화면");
    }

    @Test
    void neverSendsInvalidatedSummary() {
        ConversationMemory port = (userId, scope, question, history) -> new ConversationMemory.Recall(history, 
                new ConversationMemory.StoredSummary("s_1", USER, SESSION, "무효 요약", Instant.parse("2026-11-25T00:00:00Z")),
                List.of());

        assertThat(new AgentContextAssembler(port).assemble(USER, SESSION, "질문", HISTORY, null).summary()).isNull();
    }

    @Test
    void neverSendsSummaryOfAnotherScope() {
        ConversationMemory port = (userId, scope, question, history) -> new ConversationMemory.Recall(history, 
                new ConversationMemory.StoredSummary("s_1", USER, PERSONAL, "개인 요약", null), List.of());

        assertThat(new AgentContextAssembler(port).assemble(USER, SESSION, "질문", HISTORY, null).summary()).isNull();
    }

    @Test
    void neverSendsDeletedOrOtherScopeMemories() {
        ConversationMemory port = (userId, scope, question, history) -> new ConversationMemory.Recall(history, null, List.of(
                memory("m_ok", SESSION, null),
                memory("m_deleted", SESSION, Instant.parse("2026-11-25T00:00:00Z")),
                memory("m_other_session", OTHER_SESSION, null),
                memory("m_personal", PERSONAL, null)));

        AgentDtos.Context context = new AgentContextAssembler(port).assemble(USER, SESSION, "질문", HISTORY, null);

        assertThat(context.memories()).extracting(AgentDtos.Memory::id).containsExactly("m_ok");
    }

    @Test
    void personalScopeKeepsOnlyPersonalMemories() {
        ConversationMemory port = (userId, scope, question, history) -> new ConversationMemory.Recall(history, null, List.of(
                memory("m_session", SESSION, null), memory("m_personal", PERSONAL, null)));

        AgentDtos.Context context = new AgentContextAssembler(port).assemble(USER, PERSONAL, "질문", List.of(), null);

        assertThat(context.memories()).extracting(AgentDtos.Memory::id).containsExactly("m_personal");
    }

    @Test
    void capsMemoriesAtTwentyKeepingRelevanceOrder() {
        List<ConversationMemory.StoredMemory> many = IntStream.range(0, 25)
                .mapToObj(i -> memory("m_" + i, SESSION, null)).toList();
        ConversationMemory port = (userId, scope, question, history) -> new ConversationMemory.Recall(history, null, many);

        AgentDtos.Context context = new AgentContextAssembler(port).assemble(USER, SESSION, "질문", HISTORY, null);

        assertThat(context.memories()).hasSize(20);
        assertThat(context.memories().getFirst().id()).isEqualTo("m_0");
        assertThat(context.memories().getLast().id()).isEqualTo("m_19");
    }

    @Test
    void asksThePortOnEveryTurnSoDeletionIsSeenImmediately() {
        List<List<ConversationMemory.StoredMemory>> turns = new ArrayList<>(List.of(
                List.of(memory("m_1", SESSION, null)),
                List.of(memory("m_1", SESSION, Instant.parse("2026-11-25T00:00:00Z")))));
        List<Long> askedFor = new ArrayList<>();
        ConversationMemory port = (userId, scope, question, history) -> {
            askedFor.add(userId);
            return new ConversationMemory.Recall(history, null, turns.removeFirst());
        };
        AgentContextAssembler assembler = new AgentContextAssembler(port);

        assertThat(assembler.assemble(USER, SESSION, "질문1", HISTORY, null).memories()).hasSize(1);
        assertThat(assembler.assemble(USER, SESSION, "질문2", HISTORY, null).memories()).isEmpty();
        assertThat(askedFor).containsExactly(USER, USER);
    }

    @Test
    void passesUserScopeAndQuestionToThePort() {
        List<Object> seen = new ArrayList<>();
        ConversationMemory port = (userId, scope, question, history) -> {
            seen.addAll(List.of(userId, scope, question));
            return new ConversationMemory.Recall(history, null, List.of());
        };

        new AgentContextAssembler(port).assemble(USER, SESSION, "현재 질문", HISTORY, null);

        assertThat(seen).containsExactly(USER, SESSION, "현재 질문");
    }

    @Test
    void blankScreenSummaryIsSentAsNull() {
        ConversationMemory port = (userId, scope, question, history) -> new ConversationMemory.Recall(history, null, List.of());

        assertThat(new AgentContextAssembler(port).assemble(USER, PERSONAL, "질문", List.of(), "  ").screenSummary())
                .isNull();
    }

    @Test
    void defaultPortStoresNothing() {
        ConversationMemory.Recall recall = new NoStoredConversationMemory().recall(USER, SESSION, "질문", HISTORY);

        assertThat(recall.summary()).isNull();
        assertThat(recall.memories()).isEmpty();
    }
}
