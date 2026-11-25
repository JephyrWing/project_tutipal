package com.my.tutipal_back.learning.conversation.service;

import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
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
import com.my.tutipal_back.sessions.service.StudySessionService;

/** PT-106 Round 1 fixes (developer tests): surrogate-safe cuts, input caps, summary seat, NUL, cursor, fail-closed. */
class ConversationRound1Test {

    private static final String EMOJI = "😀";

    private final FakeConversationStore store = new FakeConversationStore();
    private final StudySessionService sessions = mock(StudySessionService.class);
    private final ConversationService service = new ConversationService(store, sessions, new MutableClock(NOW));

    private static boolean endsWithLoneHighSurrogate(String text) {
        return !text.isEmpty() && Character.isHighSurrogate(text.charAt(text.length() - 1));
    }

    // ------------------------------------------------------------------ surrogate-safe cuts

    @Test
    void storedAnswerCutNeverSplitsASurrogatePair() {
        StringBuilder answer = new StringBuilder();
        ConversationTurnService.append(answer, "a".repeat(31999));
        ConversationTurnService.append(answer, EMOJI + "tail");

        assertThat(answer.length()).isEqualTo(31999);
        assertThat(endsWithLoneHighSurrogate(answer.toString())).isFalse();
    }

    @Test
    void storedAnswerDropsNul() {
        StringBuilder answer = new StringBuilder();
        ConversationTurnService.append(answer, "a\u0000b");

        assertThat(answer.toString()).isEqualTo("ab");
    }

    @Test
    void summaryInputCutNeverSplitsASurrogatePair() {
        List<ConversationStore.Message> given = new ArrayList<>();
        given.add(new ConversationStore.Message(1L, 7L, "user", "a".repeat(7999) + EMOJI + "b", NOW));
        for (long id = 2; id <= 26; id++) {
            given.add(new ConversationStore.Message(id, 7L, id % 2 == 0 ? "assistant" : "user", "c" + id, NOW));
        }

        SummaryPlan plan = new SummaryPolicy(ConversationProperties.defaults()).plan(null, 26, given).orElseThrow();

        assertThat(plan.messages().getFirst().content()).hasSize(7999);
        assertThat(endsWithLoneHighSurrogate(plan.messages().getFirst().content())).isFalse();
    }

    @Test
    void summaryInputStopsAtTheTotalCharacterCap() {
        List<ConversationStore.Message> given = new ArrayList<>();
        for (long id = 1; id <= 40; id++) {
            given.add(new ConversationStore.Message(id, 7L, id % 2 == 1 ? "user" : "assistant", "x".repeat(8000), NOW));
        }

        SummaryPlan plan = new SummaryPolicy(ConversationProperties.defaults()).plan(null, 60, given).orElseThrow();

        // 12000 by default: one 8000 message fits, the second would exceed the cap
        assertThat(plan.messages()).hasSize(1);
        assertThat(plan.fromMessageId()).isEqualTo(1L);
        assertThat(plan.toMessageId()).isEqualTo(1L);
    }

    @Test
    void newSummaryPropertiesHaveSafeDefaults() {
        ConversationProperties.Summary summary = ConversationProperties.defaults().summary();

        assertThat(summary.maxInputChars()).isEqualTo(12000);
        assertThat(summary.maxConcurrent()).isGreaterThanOrEqualTo(1);
        assertThatThrownBy(() -> new ConversationProperties.Summary(20, 6, 40, Duration.ofSeconds(5), 0, 12000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConversationProperties.Summary(20, 6, 40, Duration.ofSeconds(5), 1, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ global summary seat

    @Test
    @SuppressWarnings("unchecked")
    void withOneSummarySeatASecondConversationIsSkippedWhileOneRuns() throws Exception {
        AiClient ai = mock(AiClient.class);
        AiSlots slots = mock(AiSlots.class);
        when(slots.run(any(Duration.class), any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        ConversationProperties properties = new ConversationProperties(0, 500,
                new ConversationProperties.Summary(20, 6, 40, Duration.ofSeconds(5), 1, 12000));
        ConversationStore.Conversation a = store.seedConversation(2L, null, "수학", NOW);
        store.seedMessages(a.id(), 30, NOW);
        ConversationStore.Conversation b = store.seedConversation(3L, null, "수학", NOW);
        store.seedMessages(b.id(), 30, NOW);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(ai.summarize(any(), anyList(), any())).thenAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return "요약";
        });
        ConversationSummarizer summarizer = new ConversationSummarizer(store, service, ai, slots,
                new SummaryPolicy(properties), properties, true);

        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> summarizer.summarizeIfNeeded(a.id()));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        summarizer.summarizeIfNeeded(b.id());           // returns at once: no free summary seat
        release.countDown();
        first.get(5, TimeUnit.SECONDS);

        verify(ai, times(1)).summarize(any(), anyList(), any());
        assertThat(store.activeSummary(b.id())).isEmpty();

        summarizer.summarizeIfNeeded(b.id());           // the seat is free again
        assertThat(store.activeSummary(b.id())).isPresent();
    }

    // ------------------------------------------------------------------ NUL, cursor

    @Test
    void nulInTopicOrMemoryIs400BeforeWriting() {
        ConversationStore.Conversation c = store.seedConversation(2L, null, "수학", NOW);

        assertThatThrownBy(() -> service.create(2L, "수\u0000학", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.saveMemory(2L, c.id(), "기\u0000억"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(store.calls).noneMatch(call -> call.equals("insertConversation") || call.startsWith("insertMemory"));
    }

    @Test
    void cursorWithAnOutOfRangeTimestampIs400() {
        for (String time : List.of("+999999999-12-31T23:59:59Z", "-999999999-01-01T00:00:00Z")) {
            String cursor = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((time + "|5").getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> service.list(2L, null, cursor, null))
                    .as(time)
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
    }

    @Test
    void scopeOfDoesNotLockTheConversation() {
        ConversationStore.Conversation c = store.seedConversation(2L, 10L, "파이썬", NOW);
        ConversationStore locking = mock(ConversationStore.class);
        when(locking.find(c.id())).thenReturn(java.util.Optional.of(c));

        assertThat(new ConversationService(locking, sessions).scopeOf(c.id())).contains(AgentDtos.Scope.session(10L));
        verify(locking, times(0)).lock(c.id());
    }

    @Test
    void savingAMemoryTakesTheScopeLockBeforeCounting() {
        List<String> order = new ArrayList<>();
        FakeConversationStore recording = new FakeConversationStore() {
            @Override
            public void lockMemoryScope(long userId, Long sessionId) {
                order.add("lockScope:" + userId + ":" + sessionId);
            }

            @Override
            public synchronized int countMemories(long userId, Long sessionId) {
                order.add("count");
                return super.countMemories(userId, sessionId);
            }
        };
        ConversationStore.Conversation c = recording.seedConversation(2L, 10L, "파이썬", NOW);

        new ConversationService(recording, sessions, new MutableClock(NOW)).saveMemory(2L, c.id(), "기억");

        assertThat(order).containsExactly("lockScope:2:10", "count");
    }

    // ------------------------------------------------------------------ fail-closed recall

    @Test
    void recallBoundToADeletedConversationUsesNoHistoryOrSummaryButKeepsScopeMemories() {
        ConversationStore.Conversation gone = store.seedConversation(2L, null, "수학", NOW);
        ConversationStore.Summary summary = store.seedSummary(gone.id(), "지운 요약", 1, 2, NOW);
        ConversationStore.Conversation kept = store.seedConversation(2L, null, "수학", NOW);
        ConversationStore.Memory memory = store.seedMemory(2L, null, kept.id(), "남는 기억", NOW);
        store.deleteConversation(gone.id());
        ConversationBinding binding = new ConversationBinding();
        StoredConversationMemory port = new StoredConversationMemory(store, binding);
        List<AgentDtos.ContextMessage> history = List.of(new AgentDtos.ContextMessage("user", "지운 대화 원문"));

        ConversationMemory.Recall[] recall = new ConversationMemory.Recall[1];
        binding.run(new ConversationBinding.Bound(2L, AgentDtos.Scope.personal(), gone.id(), summary.id(), history),
                () -> recall[0] = port.recall(2L, AgentDtos.Scope.personal(), "질문", List.of()));

        assertThat(recall[0].messages()).isEmpty();
        assertThat(recall[0].summary()).isNull();
        assertThat(recall[0].memories()).extracting(ConversationMemory.StoredMemory::id)
                .containsExactly("m_" + memory.id());
    }
}
