package com.my.tutipal_back.learning.conversation.service;

import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.http.HttpStatus;

import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.ai.client.AiSlots;
import com.my.tutipal_back.ai.dto.AgentDtos;
import com.my.tutipal_back.ai.quota.MutableClock;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.learning.conversation.ConversationProperties;
import com.my.tutipal_back.learning.conversation.repository.ConversationStore;
import com.my.tutipal_back.learning.conversation.repository.FakeConversationStore;
import com.my.tutipal_back.learning.tutor.service.ConversationMemory;
import com.my.tutipal_back.sessions.entity.SessionRole;
import com.my.tutipal_back.sessions.service.StudySessionService;

/**
 * PT-106 soft delete (PM decision): a deleted conversation is invisible everywhere for everyone (404 like a missing
 * one), its messages / summaries / memories never reach recall or the summarizer, nothing is saved into it, and the
 * memory cap counts live memories only. A deleted memory is excluded at once.
 */
class ConversationSoftDeleteTest {

    private static final long ME = 2L;
    private static final long SESSION = 10L;
    private static final UUID KEY = UUID.fromString("00000000-0000-4000-8000-000000000106");

    private final FakeConversationStore store = new FakeConversationStore();
    private final StudySessionService sessions = mock(StudySessionService.class);
    private final MutableClock clock = new MutableClock(NOW);
    private final ConversationService conversations = new ConversationService(store, sessions, clock);

    @BeforeEach
    void setUp() {
        when(sessions.requireActiveRole(ME, SESSION)).thenReturn(SessionRole.STUDENT);
    }

    private static void assertStatus(Executable call, HttpStatus status) {
        assertThatThrownBy(call::execute).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(status));
    }

    private ConversationStore.Conversation deletedWithEverything() {
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);
        store.seedMessages(c.id(), 4, NOW);
        store.insertPair(c.id(), "키 질문", "키 답", "키 질문", NOW, KEY);
        store.seedSummary(c.id(), "지운 대화 요약", 1, 2, NOW);
        store.seedMemory(ME, null, c.id(), "지운 대화 기억", NOW);
        conversations.delete(ME, c.id());
        return c;
    }

    @Test
    void deleteSetsDeletedAtAndKeepsTheRowsForThePurge() {
        ConversationStore.Conversation c = deletedWithEverything();

        assertThat(store.deletedAt(c.id())).contains(NOW);
        assertThat(store.conversation(c.id())).isPresent();
        assertThat(store.messagesOf(c.id())).hasSize(6);
        assertThat(store.allMemories()).hasSize(1);
    }

    @Test
    void deletedConversationIs404EverywhereLikeAMissingOne() {
        ConversationStore.Conversation c = deletedWithEverything();
        long memoryId = store.allMemories().getFirst().id();
        String before = store.snapshot();

        assertThat(conversations.list(ME, null, null, null).items()).isEmpty();
        assertStatus(() -> conversations.detail(ME, c.id()), HttpStatus.NOT_FOUND);
        assertStatus(() -> conversations.messages(ME, c.id(), null, null), HttpStatus.NOT_FOUND);
        assertStatus(() -> conversations.delete(ME, c.id()), HttpStatus.NOT_FOUND);
        assertStatus(() -> conversations.saveMemory(ME, c.id(), "새 기억"), HttpStatus.NOT_FOUND);
        assertStatus(() -> conversations.deleteMemory(ME, c.id(), memoryId), HttpStatus.NOT_FOUND);
        assertStatus(() -> conversations.prepareTurn(ME, c.id(), "질문"), HttpStatus.NOT_FOUND);
        assertStatus(() -> conversations.findSaved(ME, c.id(), KEY, "키 질문"), HttpStatus.NOT_FOUND);

        assertThat(store.snapshot()).isEqualTo(before);
        assertThat(store.deletedAt(c.id())).contains(NOW);
    }

    /** A turn that was streaming when the conversation was deleted: the existing 410 "삭제되어 저장하지 않았어요". */
    @Test
    void savePairTreatsADeletedConversationAsDeleted() {
        ConversationStore.Conversation c = deletedWithEverything();
        int messages = store.messagesOf(c.id()).size();
        long pairs = store.calls.stream().filter(call -> call.startsWith("insertPair:")).count();

        assertThatThrownBy(() -> conversations.savePair(ME, c.id(), "질문", "답"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.GONE);
                    assertThat(e.getMessage()).isEqualTo(ConversationService.DELETED_WHILE_STREAMING);
                });
        assertStatus(() -> conversations.savePair(ME, c.id(), "키 질문", "답", KEY), HttpStatus.GONE);

        assertThat(store.messagesOf(c.id())).hasSize(messages);
        assertThat(store.calls.stream().filter(call -> call.startsWith("insertPair:")).count()).isEqualTo(pairs);
    }

    @Test
    void recallBoundToADeletedConversationGetsNothingOfIt() {
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);
        ConversationStore.Summary summary = store.seedSummary(c.id(), "지운 요약", 1, 2, NOW);
        store.seedMemory(ME, null, c.id(), "지운 대화 기억", NOW);
        conversations.delete(ME, c.id());

        ConversationMemory.Recall recall = recall(new ConversationBinding.Bound(ME, AgentDtos.Scope.personal(),
                c.id(), summary.id(), List.of(new AgentDtos.ContextMessage("user", "지운 원문"))));

        assertThat(recall.messages()).isEmpty();
        assertThat(recall.summary()).isNull();
        assertThat(recall.memories()).isEmpty();
    }

    /** Memories of a deleted conversation are not offered to other conversations of the same scope. */
    @Test
    void memoriesOfADeletedConversationAreExcludedFromAnotherConversationsRecall() {
        ConversationStore.Conversation gone = store.seedConversation(ME, SESSION, "파이썬", NOW);
        store.seedMemory(ME, SESSION, gone.id(), "지운 대화 기억", NOW.plusSeconds(5));
        ConversationStore.Conversation kept = store.seedConversation(ME, SESSION, "파이썬", NOW);
        ConversationStore.Memory keptMemory = store.seedMemory(ME, SESSION, kept.id(), "남는 기억", NOW);
        conversations.delete(ME, gone.id());

        ConversationMemory.Recall recall = recall(new ConversationBinding.Bound(ME, AgentDtos.Scope.session(SESSION),
                kept.id(), null, List.of()));

        assertThat(recall.memories()).extracting(ConversationMemory.StoredMemory::id)
                .containsExactly("m_" + keptMemory.id());
        assertThat(recall.memories()).extracting(ConversationMemory.StoredMemory::text)
                .doesNotContain("지운 대화 기억");
    }

    private ConversationMemory.Recall recall(ConversationBinding.Bound bound) {
        ConversationBinding binding = new ConversationBinding();
        StoredConversationMemory port = new StoredConversationMemory(store, binding);
        ConversationMemory.Recall[] out = new ConversationMemory.Recall[1];
        binding.run(bound, () -> out[0] = port.recall(bound.userId(), bound.scope(), "질문", List.of()));
        return out[0];
    }

    @Test
    @SuppressWarnings("unchecked")
    void summarizerNeverReadsOrSavesIntoADeletedConversation() {
        ConversationStore.Conversation c = store.seedConversation(ME, SESSION, "파이썬", NOW);
        store.seedMessages(c.id(), 40, NOW);
        conversations.delete(ME, c.id());
        AiClient ai = mock(AiClient.class);
        AiSlots slots = mock(AiSlots.class);
        when(slots.run(any(Duration.class), any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        ConversationProperties properties = ConversationProperties.defaults();

        new ConversationSummarizer(store, conversations, ai, slots, new SummaryPolicy(properties), properties, true)
                .summarizeIfNeeded(c.id());

        verifyNoInteractions(ai);
        assertThat(store.allSummaries()).isEmpty();
        assertThat(conversations.saveSummary(c.id(), new SummaryPlan(null, null, List.of(), 1, 10), "요약")).isFalse();
        assertThat(store.allSummaries()).isEmpty();
    }

    // ------------------------------------------------------------------ memory soft delete and the cap

    @Test
    void deletedMemoryIsSoftDeletedAndExcludedFromDetailAndRecallAtOnce() {
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);
        ConversationStore.Memory gone = store.seedMemory(ME, null, c.id(), "지울 기억", NOW);
        ConversationStore.Memory kept = store.seedMemory(ME, null, c.id(), "남길 기억", NOW);

        conversations.deleteMemory(ME, c.id(), gone.id());

        assertThat(store.memoryDeletedAt(gone.id())).contains(NOW);
        assertThat(store.allMemories()).extracting(ConversationStore.Memory::id).contains(gone.id());
        assertThat(conversations.detail(ME, c.id()).memories()).extracting(m -> m.id())
                .containsExactly(String.valueOf(kept.id()));
        assertThat(recall(new ConversationBinding.Bound(ME, AgentDtos.Scope.personal(), c.id(), null, List.of()))
                .memories()).extracting(ConversationMemory.StoredMemory::id).containsExactly("m_" + kept.id());
        assertStatus(() -> conversations.deleteMemory(ME, c.id(), gone.id()), HttpStatus.NOT_FOUND);
    }

    @Test
    void capCountsOnlyLiveMemories() {
        ConversationStore.Conversation c = store.seedConversation(ME, SESSION, "파이썬", NOW);
        List<ConversationStore.Memory> twenty = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            twenty.add(store.seedMemory(ME, SESSION, c.id(), "기억 " + i, NOW));
        }
        assertStatus(() -> conversations.saveMemory(ME, c.id(), "하나 더"), HttpStatus.CONFLICT);

        conversations.deleteMemory(ME, c.id(), twenty.getFirst().id());

        assertThat(conversations.saveMemory(ME, c.id(), "자리 생김").text()).isEqualTo("자리 생김");
        assertStatus(() -> conversations.saveMemory(ME, c.id(), "또 넘침"), HttpStatus.CONFLICT);
    }

    @Test
    void memoriesOfADeletedConversationDoNotCountTowardTheCap() {
        ConversationStore.Conversation gone = store.seedConversation(ME, SESSION, "파이썬", NOW);
        for (int i = 0; i < 20; i++) {
            store.seedMemory(ME, SESSION, gone.id(), "기억 " + i, NOW);
        }
        ConversationStore.Conversation other = store.seedConversation(ME, SESSION, "파이썬", NOW);
        assertStatus(() -> conversations.saveMemory(ME, other.id(), "넘침"), HttpStatus.CONFLICT);

        conversations.delete(ME, gone.id());

        assertThat(store.countMemories(ME, SESSION)).isZero();
        assertThat(conversations.saveMemory(ME, other.id(), "삭제 뒤 저장").text()).isEqualTo("삭제 뒤 저장");
    }
}
