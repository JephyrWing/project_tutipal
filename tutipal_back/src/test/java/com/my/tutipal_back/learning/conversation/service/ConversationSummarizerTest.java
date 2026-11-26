package com.my.tutipal_back.learning.conversation.service;

import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;

import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.ai.client.AiSlots;
import com.my.tutipal_back.ai.client.AiUnavailableException;
import com.my.tutipal_back.ai.dto.AgentDtos;
import com.my.tutipal_back.ai.quota.MutableClock;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.learning.conversation.ConversationProperties;
import com.my.tutipal_back.learning.conversation.repository.ConversationStore;
import com.my.tutipal_back.learning.conversation.repository.FakeConversationStore;
import com.my.tutipal_back.sessions.service.StudySessionService;

/**
 * PT-106 AC-21 (+ AC-12 summary half): the summary runs outside the turn, at most one per conversation,
 * with a short AiSlots wait, without the CHAT quota, keeps the previous summary on failure, discards the result
 * when the active summary changed meanwhile, and saves nothing when the conversation was deleted meanwhile.
 */
@ExtendWith(OutputCaptureExtension.class)
class ConversationSummarizerTest {

    private final FakeConversationStore store = new FakeConversationStore();
    private final StudySessionService sessions = mock(StudySessionService.class);
    private final AiClient ai = mock(AiClient.class);
    private final AiSlots slots = mock(AiSlots.class);
    private final MutableClock clock = new MutableClock(NOW);
    private final ConversationProperties properties = ConversationProperties.defaults();
    private ConversationService conversations;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(slots.run(any(Duration.class), any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        when(slots.run(any(Supplier.class))).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
        conversations = new ConversationService(store, sessions, clock);
    }

    private ConversationSummarizer summarizer(boolean agentEnabled) {
        return new ConversationSummarizer(store, conversations, ai, slots, new SummaryPolicy(properties), properties,
                agentEnabled);
    }

    /** Session-10 conversation with `count` messages "m1".."m<count>" and an active summary over the first 10. */
    private record Seeded(ConversationStore.Conversation conversation, List<ConversationStore.Message> messages,
                          ConversationStore.Summary summary) {
    }

    private Seeded seed(int count) {
        ConversationStore.Conversation c = store.seedConversation(2L, 10L, "파이썬", NOW);
        List<ConversationStore.Message> messages = store.seedMessages(c.id(), count, NOW);
        ConversationStore.Summary s = store.seedSummary(c.id(), "SECRET-PREVIOUS-SUMMARY", messages.get(0).id(),
                messages.get(9).id(), NOW);
        return new Seeded(c, messages, s);
    }

    @Test
    void successSupersedesTheActiveSummaryAndInsertsTheNewOneWithItsRange() {
        Seeded s = seed(40);
        when(ai.summarize(any(), anyList(), any())).thenReturn("새 요약");

        summarizer(true).summarizeIfNeeded(s.conversation().id());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentDtos.ContextMessage>> sent = ArgumentCaptor.forClass(List.class);
        verify(ai).summarize(eq(AgentDtos.Scope.session(10L)), sent.capture(), eq("SECRET-PREVIOUS-SUMMARY"));
        // 30 after the summary, 24 eligible (11..34)
        assertThat(sent.getValue()).hasSize(24);
        assertThat(sent.getValue().getFirst().content()).isEqualTo("m11");
        assertThat(sent.getValue().getLast().content()).isEqualTo("m34");

        assertThat(store.summary(s.summary().id()).orElseThrow().supersededAt()).isNotNull();
        ConversationStore.Summary now = store.activeSummary(s.conversation().id()).orElseThrow();
        assertThat(now.text()).isEqualTo("새 요약");
        assertThat(now.fromMessageId()).isEqualTo(s.messages().get(10).id());
        assertThat(now.toMessageId()).isEqualTo(s.messages().get(33).id());
        assertThat(store.summariesOf(s.conversation().id())).hasSize(2);
    }

    @Test
    void personalConversationSummaryUsesPersonalScopeAndNoPreviousSummary() {
        ConversationStore.Conversation c = store.seedConversation(2L, null, "수학", NOW);
        store.seedMessages(c.id(), 26, NOW);
        when(ai.summarize(any(), anyList(), any())).thenReturn("첫 요약");

        summarizer(true).summarizeIfNeeded(c.id());

        verify(ai).summarize(eq(AgentDtos.Scope.personal()), anyList(), isNull());
        assertThat(store.activeSummary(c.id()).orElseThrow().text()).isEqualTo("첫 요약");
    }

    @Test
    void belowThresholdDoesNotCallTheModel() {
        ConversationStore.Conversation c = store.seedConversation(2L, null, "수학", NOW);
        store.seedMessages(c.id(), 25, NOW);

        summarizer(true).summarizeIfNeeded(c.id());

        verifyNoInteractions(ai);
        assertThat(store.summariesOf(c.id())).isEmpty();
    }

    @Test
    void agentDisabledSkipsTheSummary() {
        Seeded s = seed(60);

        summarizer(false).summarizeIfNeeded(s.conversation().id());

        verifyNoInteractions(ai);
        verifyNoInteractions(slots);
        assertThat(store.activeSummary(s.conversation().id()).orElseThrow().id()).isEqualTo(s.summary().id());
    }

    @Test
    void usesAShortConfiguredSlotWait() {
        Seeded s = seed(40);
        when(ai.summarize(any(), anyList(), any())).thenReturn("새 요약");

        summarizer(true).summarizeIfNeeded(s.conversation().id());

        verify(slots).run(eq(Duration.ofSeconds(5)), any());
    }

    @Test
    void busySlotsSkipTheSummaryQuietly() {
        Seeded s = seed(40);
        when(slots.run(any(Duration.class), any()))
                .thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "지금 질문이 많아요"));

        assertThatCode(() -> summarizer(true).summarizeIfNeeded(s.conversation().id())).doesNotThrowAnyException();

        verify(ai, never()).summarize(any(), anyList(), any());
        assertThat(store.activeSummary(s.conversation().id()).orElseThrow().id()).isEqualTo(s.summary().id());
    }

    @Test
    void failureKeepsThePreviousSummaryAndLogsNoContent(CapturedOutput output) {
        for (int i = 1; i < 4242; i++) {
            store.seedConversation(9L, null, "자리", NOW);       // make the conversation id distinctive (4242)
        }
        ConversationStore.Conversation c = store.seedConversation(2L, 10L, "파이썬", NOW);
        for (int i = 0; i < 40; i++) {
            store.seedMessage(c.id(), i % 2 == 0 ? "user" : "assistant", "SECRET-MESSAGE-" + i, NOW);
        }
        List<ConversationStore.Message> all = store.messagesOf(c.id());
        ConversationStore.Summary previous = store.seedSummary(c.id(), "SECRET-PREVIOUS-SUMMARY", all.get(0).id(),
                all.get(9).id(), NOW);
        when(ai.summarize(any(), anyList(), any())).thenThrow(new AiUnavailableException());

        assertThatCode(() -> summarizer(true).summarizeIfNeeded(c.id())).doesNotThrowAnyException();

        assertThat(store.activeSummary(c.id()).orElseThrow().id()).isEqualTo(previous.id());
        assertThat(store.summariesOf(c.id())).hasSize(1);
        assertThat(output.getAll()).doesNotContain("SECRET-MESSAGE", "SECRET-PREVIOUS-SUMMARY");
        assertThat(c.id()).isEqualTo(4242L);
        assertThat(output.getAll()).contains("4242");
    }

    @Test
    void resultIsDiscardedWhenTheActiveSummaryChangedMeanwhile() {
        Seeded s = seed(40);
        when(ai.summarize(any(), anyList(), any())).thenAnswer(inv -> {
            store.seedSummary(s.conversation().id(), "다른 요청이 만든 요약", s.messages().get(10).id(),
                    s.messages().get(20).id(), NOW);
            return "늦게 온 요약";
        });

        assertThatCode(() -> summarizer(true).summarizeIfNeeded(s.conversation().id())).doesNotThrowAnyException();

        assertThat(store.activeSummary(s.conversation().id()).orElseThrow().text()).isEqualTo("다른 요청이 만든 요약");
        assertThat(store.summariesOf(s.conversation().id())).extracting(ConversationStore.Summary::text)
                .doesNotContain("늦게 온 요약");
    }

    @Test
    void conversationDeletedDuringTheSummarySavesNothingAndDoesNotThrow() {
        Seeded s = seed(40);
        when(ai.summarize(any(), anyList(), any())).thenAnswer(inv -> {
            store.deleteConversation(s.conversation().id());
            return "늦게 온 요약";
        });

        assertThatCode(() -> summarizer(true).summarizeIfNeeded(s.conversation().id())).doesNotThrowAnyException();

        // soft delete: the earlier summary row waits for the purge untouched; nothing new was written
        assertThat(store.allSummaries()).containsExactly(s.summary());
        assertThat(store.calls).noneMatch(call -> call.startsWith("insertSummary") || call.startsWith("supersede"));
        assertThat(store.activeSummary(s.conversation().id())).isEmpty();
        assertThat(store.deletedAt(s.conversation().id())).isPresent();
    }

    /** Deleted while waiting for a seat / AiSlots: re-checked before the model call, so its text is never sent. */
    @Test
    @SuppressWarnings("unchecked")
    void conversationDeletedWhileWaitingForASlotIsNeverSentToTheModel() {
        Seeded s = seed(40);
        when(slots.run(any(Duration.class), any(Supplier.class))).thenAnswer(inv -> {
            store.deleteConversation(s.conversation().id());
            return ((Supplier<?>) inv.getArgument(1)).get();
        });

        assertThatCode(() -> summarizer(true).summarizeIfNeeded(s.conversation().id())).doesNotThrowAnyException();

        verifyNoInteractions(ai);
        assertThat(store.calls).noneMatch(call -> call.startsWith("insertSummary") || call.startsWith("supersede"));
    }

    @Test
    void missingConversationIsIgnored() {
        assertThatCode(() -> summarizer(true).summarizeIfNeeded(999L)).doesNotThrowAnyException();

        verifyNoInteractions(ai);
    }

    @Test
    void atMostOneSummaryPerConversationRunsAtATime() throws Exception {
        Seeded s = seed(40);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(ai.summarize(any(), anyList(), any())).thenAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return "새 요약";
        });
        ConversationSummarizer summarizer = summarizer(true);

        CompletableFuture<Void> first = CompletableFuture.runAsync(
                () -> summarizer.summarizeIfNeeded(s.conversation().id()));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> second = CompletableFuture.runAsync(
                () -> summarizer.summarizeIfNeeded(s.conversation().id()));
        second.get(2, TimeUnit.SECONDS);          // returns without waiting for the first
        release.countDown();
        first.get(5, TimeUnit.SECONDS);

        verify(ai, times(1)).summarize(any(), anyList(), any());
        assertThat(store.summariesOf(s.conversation().id())).hasSize(2);
    }

    @Test
    void differentConversationsCanSummarizeConcurrently() throws Exception {
        Seeded a = seed(40);
        Seeded b = seed(40);
        CountDownLatch bothEntered = new CountDownLatch(2);
        when(ai.summarize(any(), anyList(), any())).thenAnswer(inv -> {
            bothEntered.countDown();
            bothEntered.await(5, TimeUnit.SECONDS);
            return "요약";
        });
        ConversationSummarizer summarizer = summarizer(true);

        CompletableFuture<Void> first = CompletableFuture.runAsync(
                () -> summarizer.summarizeIfNeeded(a.conversation().id()));
        CompletableFuture<Void> second = CompletableFuture.runAsync(
                () -> summarizer.summarizeIfNeeded(b.conversation().id()));
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);

        assertThat(bothEntered.getCount()).isZero();
        verify(ai, times(2)).summarize(any(), anyList(), any());
    }
}
