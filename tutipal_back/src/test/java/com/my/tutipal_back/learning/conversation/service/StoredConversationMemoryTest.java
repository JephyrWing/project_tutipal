package com.my.tutipal_back.learning.conversation.service;

import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.my.tutipal_back.ai.dto.AgentDtos;
import com.my.tutipal_back.learning.conversation.repository.ConversationStore;
import com.my.tutipal_back.learning.conversation.repository.FakeConversationStore;
import com.my.tutipal_back.learning.tutor.service.AgentContextAssembler;
import com.my.tutipal_back.learning.tutor.service.ConversationMemory;

/**
 * PT-106 StoredConversationMemory (the ConversationMemory port implementation): bound recall (AC-2),
 * unbound / mismatched recall uses nothing stored (AC-3), invalid summary filtered by the assembler (AC-11).
 */
class StoredConversationMemoryTest {

    private static final long ME = 2L;
    private static final AgentDtos.Scope SESSION = AgentDtos.Scope.session(10L);
    private static final List<AgentDtos.ContextMessage> SCREEN =
            List.of(new AgentDtos.ContextMessage("user", "화면 질문"), new AgentDtos.ContextMessage("assistant", "화면 답"));
    private static final List<AgentDtos.ContextMessage> SERVER =
            List.of(new AgentDtos.ContextMessage("user", "서버 질문"), new AgentDtos.ContextMessage("assistant", "서버 답"));

    private final FakeConversationStore store = new FakeConversationStore();
    private final ConversationBinding binding = new ConversationBinding();
    private final StoredConversationMemory memory = new StoredConversationMemory(store, binding);

    private ConversationMemory.Recall recallBound(ConversationBinding.Bound bound, long userId,
                                                  AgentDtos.Scope scope) {
        ConversationMemory.Recall[] out = new ConversationMemory.Recall[1];
        binding.run(bound, () -> out[0] = memory.recall(userId, scope, "질문", SCREEN));
        return out[0];
    }

    @Test
    void unboundRecallReturnsScreenHistoryAndNothingStoredWithoutTouchingTheStore() {
        ConversationStore mocked = mock(ConversationStore.class);

        ConversationMemory.Recall recall = new StoredConversationMemory(mocked, binding).recall(ME, SESSION, "질문",
                SCREEN);

        assertThat(recall.messages()).isEqualTo(SCREEN);
        assertThat(recall.summary()).isNull();
        assertThat(recall.memories()).isEmpty();
        verifyNoInteractions(mocked);
    }

    @Test
    void unboundRecallWithNullScreenHistoryIsEmptyHistory() {
        ConversationMemory.Recall recall = memory.recall(ME, SESSION, "질문", null);

        assertThat(recall.messages()).isEmpty();
        assertThat(recall.summary()).isNull();
        assertThat(recall.memories()).isEmpty();
    }

    @Test
    void boundRecallReturnsServerHistoryBoundSummaryAndScopeMemories() {
        ConversationStore.Conversation c = store.seedConversation(ME, 10L, "파이썬", NOW);
        store.seedMessages(c.id(), 4, NOW);
        ConversationStore.Summary summary = store.seedSummary(c.id(), "요약 본문", 1, 2, NOW);
        ConversationStore.Memory older = store.seedMemory(ME, 10L, c.id(), "옛 기억", NOW);
        ConversationStore.Memory newer = store.seedMemory(ME, 10L, c.id(), "새 기억", NOW.plusSeconds(5));

        ConversationMemory.Recall recall = recallBound(
                new ConversationBinding.Bound(ME, SESSION, c.id(), summary.id(), SERVER), ME, SESSION);

        assertThat(recall.messages()).isEqualTo(SERVER);
        assertThat(recall.summary().id()).isEqualTo("s_" + summary.id());
        assertThat(recall.summary().ownerUserId()).isEqualTo(ME);
        assertThat(recall.summary().scope()).isEqualTo(SESSION);
        assertThat(recall.summary().text()).isEqualTo("요약 본문");
        assertThat(recall.summary().invalidatedAt()).isNull();
        assertThat(recall.memories()).extracting(ConversationMemory.StoredMemory::id)
                .containsExactly("m_" + newer.id(), "m_" + older.id());
        assertThat(recall.memories()).allSatisfy(m -> {
            assertThat(m.ownerUserId()).isEqualTo(ME);
            assertThat(m.scope()).isEqualTo(SESSION);
            assertThat(m.deletedAt()).isNull();
        });
    }

    @Test
    void boundRecallReturnsAtMost20Memories() {
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);
        for (int i = 0; i < 25; i++) {
            store.seedMemory(ME, null, c.id(), "기억 " + i, NOW.plusSeconds(i));
        }

        ConversationMemory.Recall recall = recallBound(
                new ConversationBinding.Bound(ME, AgentDtos.Scope.personal(), c.id(), null, SERVER), ME,
                AgentDtos.Scope.personal());

        assertThat(recall.memories()).hasSize(20);
        assertThat(recall.memories().getFirst().text()).isEqualTo("기억 24");
        assertThat(recall.summary()).isNull();
    }

    @Test
    void personalScopeQueriesPersonalMemoriesOnly() {
        ConversationStore.Conversation personal = store.seedConversation(ME, null, "수학", NOW);
        ConversationStore.Conversation session = store.seedConversation(ME, 10L, "파이썬", NOW);
        store.seedMemory(ME, 10L, session.id(), "수업 기억", NOW);
        ConversationStore.Memory mine = store.seedMemory(ME, null, personal.id(), "개인 기억", NOW);

        ConversationMemory.Recall recall = recallBound(
                new ConversationBinding.Bound(ME, AgentDtos.Scope.personal(), personal.id(), null, SERVER), ME,
                AgentDtos.Scope.personal());

        assertThat(recall.memories()).extracting(ConversationMemory.StoredMemory::id).containsExactly("m_" + mine.id());
        assertThat(store.memoryQueries).allSatisfy(args -> {
            assertThat(args[0]).isEqualTo(ME);
            assertThat(args[1]).isNull();
        });
    }

    @Test
    void userMismatchUsesNothingStored() {
        ConversationStore.Conversation c = store.seedConversation(ME, 10L, "파이썬", NOW);
        ConversationStore.Summary summary = store.seedSummary(c.id(), "요약", 1, 2, NOW);
        store.seedMemory(ME, 10L, c.id(), "기억", NOW);

        ConversationMemory.Recall recall = recallBound(
                new ConversationBinding.Bound(ME, SESSION, c.id(), summary.id(), SERVER), 3L, SESSION);

        assertThat(recall.messages()).isEqualTo(SCREEN);
        assertThat(recall.summary()).isNull();
        assertThat(recall.memories()).isEmpty();
        assertThat(store.memoryQueries).isEmpty();
    }

    @Test
    void scopeMismatchUsesNothingStored() {
        ConversationStore.Conversation c = store.seedConversation(ME, 10L, "파이썬", NOW);
        ConversationStore.Summary summary = store.seedSummary(c.id(), "요약", 1, 2, NOW);
        store.seedMemory(ME, 10L, c.id(), "기억", NOW);
        ConversationBinding.Bound bound = new ConversationBinding.Bound(ME, SESSION, c.id(), summary.id(), SERVER);

        for (AgentDtos.Scope asked : List.of(AgentDtos.Scope.session(11L), AgentDtos.Scope.personal())) {
            ConversationMemory.Recall recall = recallBound(bound, ME, asked);
            assertThat(recall.messages()).isEqualTo(SCREEN);
            assertThat(recall.summary()).isNull();
            assertThat(recall.memories()).isEmpty();
        }
        assertThat(store.memoryQueries).isEmpty();
    }

    @Test
    void summaryOfAnotherConversationIsNeverReturned() {
        ConversationStore.Conversation c = store.seedConversation(ME, 10L, "파이썬", NOW);
        ConversationStore.Conversation other = store.seedConversation(3L, 10L, "파이썬", NOW);
        ConversationStore.Summary foreign = store.seedSummary(other.id(), "남의 요약", 1, 2, NOW);

        ConversationMemory.Recall recall = recallBound(
                new ConversationBinding.Bound(ME, SESSION, c.id(), foreign.id(), SERVER), ME, SESSION);

        assertThat(recall.summary()).isNull();
    }

    @Test
    void supersededBoundSummaryIsStillReturned() {
        ConversationStore.Conversation c = store.seedConversation(ME, 10L, "파이썬", NOW);
        ConversationStore.Summary bound = store.seedSummary(c.id(), "묶인 요약", 1, 2, NOW);
        store.seedSummary(c.id(), "더 새 요약", 3, 4, NOW.plusSeconds(1));

        ConversationMemory.Recall recall = recallBound(
                new ConversationBinding.Bound(ME, SESSION, c.id(), bound.id(), SERVER), ME, SESSION);

        assertThat(recall.summary().id()).isEqualTo("s_" + bound.id());
        assertThat(recall.summary().text()).isEqualTo("묶인 요약");
    }

    @Test
    void deletedBoundSummaryIsNull() {
        ConversationStore.Conversation c = store.seedConversation(ME, 10L, "파이썬", NOW);
        ConversationStore.Summary bound = store.seedSummary(c.id(), "요약", 1, 2, NOW);
        store.removeSummary(bound.id());

        ConversationMemory.Recall recall = recallBound(
                new ConversationBinding.Bound(ME, SESSION, c.id(), bound.id(), SERVER), ME, SESSION);

        assertThat(recall.summary()).isNull();
    }

    @Test
    void invalidatedSummaryNeverReachesTheAgentContext() {
        ConversationStore.Conversation c = store.seedConversation(ME, 10L, "파이썬", NOW);
        ConversationStore.Summary bound = store.seedSummary(c.id(), "무효 요약", 1, 2, NOW);
        store.invalidate(bound.id(), NOW);
        AgentContextAssembler assembler = new AgentContextAssembler(memory);

        AgentDtos.Context[] context = new AgentDtos.Context[1];
        binding.run(new ConversationBinding.Bound(ME, SESSION, c.id(), bound.id(), SERVER),
                () -> context[0] = assembler.assemble(ME, SESSION, "질문", SCREEN, null));

        assertThat(context[0].summary()).isNull();
        assertThat(context[0].messages()).isEqualTo(SERVER);
    }

    @Test
    void storedIdsMatchTheContractPattern() {
        ConversationStore.Conversation c = store.seedConversation(ME, 10L, "파이썬", NOW);
        ConversationStore.Summary summary = store.seedSummary(c.id(), "요약", 1, 2, NOW);
        store.seedMemory(ME, 10L, c.id(), "기억", NOW);

        ConversationMemory.Recall recall = recallBound(
                new ConversationBinding.Bound(ME, SESSION, c.id(), summary.id(), SERVER), ME, SESSION);

        assertThat(recall.summary().id()).matches("^s_[0-9]+$").matches("^[A-Za-z0-9_-]{1,64}$");
        assertThat(recall.memories()).extracting(ConversationMemory.StoredMemory::id)
                .allMatch(id -> id.matches("^m_[0-9]+$"));
    }
}
