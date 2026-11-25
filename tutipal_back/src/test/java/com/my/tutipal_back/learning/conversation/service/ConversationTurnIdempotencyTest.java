package com.my.tutipal_back.learning.conversation.service;

import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.JSON;
import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.NOW;
import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.scriptTutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

import com.my.tutipal_back.ai.quota.MutableClock;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.learning.conversation.dto.ConversationDtos;
import com.my.tutipal_back.learning.conversation.repository.ConversationStore;
import com.my.tutipal_back.learning.conversation.repository.FakeConversationStore;
import com.my.tutipal_back.learning.tutor.service.OpenedChat;
import com.my.tutipal_back.learning.tutor.service.TutorService;
import com.my.tutipal_back.sessions.service.StudySessionService;

/**
 * PT-106 Codex re-review (developer test): idempotency key in the turn service. A replay holds no quota and runs
 * no model; two requests with the same key that both ran the model (two servers: the per-user guard is in memory)
 * still store one pair, whether the second one sees the first under the row lock or hits the unique index.
 */
class ConversationTurnIdempotencyTest {

    private static final long ME = 2L;
    private static final UUID KEY = UUID.fromString("5d1c0e2a-8a3b-4c6d-9e7f-0a1b2c3d4e5f");

    private final StudySessionService sessions = mock(StudySessionService.class);
    private final TutorService tutor = mock(TutorService.class);
    private final OpenedChat opened = mock(OpenedChat.class);
    private final ConversationSummarizer summarizer = mock(ConversationSummarizer.class);

    private ConversationTurnService turnsOn(ConversationStore store) {
        return new ConversationTurnService(new ConversationService(store, sessions, new MutableClock(NOW)), tutor,
                sessions, new ConversationBinding(), summarizer);
    }

    private static ConversationDtos.Saved run(ConversationTurnService turns, ConversationTurnService.OpenedTurn turn,
                                              List<String> deltas) {
        return turns.run(turn, sources -> { }, deltas::add, event -> { }, () -> { });
    }

    private static long insertPairs(FakeConversationStore store) {
        return store.calls.stream().filter(call -> call.startsWith("insertPair:")).count();
    }

    @Test
    void replayDoesNotOpenAChatAndReleasesNothingOnFinish() {
        FakeConversationStore store = new FakeConversationStore();
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);
        ConversationStore.SavedPair stored = store.insertPair(c.id(), "질문", "답", "질문", NOW, KEY);
        ConversationTurnService turns = turnsOn(store);

        ConversationTurnService.OpenedTurn turn = turns.open(ME, c.id(), "질문", KEY);
        List<String> deltas = new ArrayList<>();
        ConversationDtos.Saved saved = run(turns, turn, deltas);
        turns.finish(turn, true);

        assertThat(turn.isReplay()).isTrue();
        assertThat(deltas).containsExactly("답");
        assertThat(saved).isEqualTo(new ConversationDtos.Saved(String.valueOf(stored.user().id()),
                String.valueOf(stored.assistant().id())));
        verifyNoInteractions(tutor, summarizer);
        assertThat(insertPairs(store)).isEqualTo(1);
        // the user's turn slot is free again
        when(tutor.openChat(anyLong(), any())).thenReturn(opened);
        turns.finish(turns.open(ME, c.id(), "다른 질문", UUID.randomUUID()), false);
    }

    @Test
    void differentTextUnderAUsedKeyIs409BeforeTheQuota() {
        FakeConversationStore store = new FakeConversationStore();
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);
        store.insertPair(c.id(), "질문", "답", "질문", NOW, KEY);
        ConversationTurnService turns = turnsOn(store);

        assertThatThrownBy(() -> turns.open(ME, c.id(), "바꾼 질문", KEY))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verifyNoInteractions(tutor);
    }

    @Test
    void secondRequestThatRanTheModelGetsThePairStoredByTheFirst() {
        FakeConversationStore store = new FakeConversationStore();
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);
        when(tutor.openChat(anyLong(), any())).thenReturn(opened);
        scriptTutor(tutor, (request, onDelta) -> onDelta.accept("답"));
        ConversationTurnService serverA = turnsOn(store);
        ConversationTurnService serverB = turnsOn(store);

        ConversationTurnService.OpenedTurn a = serverA.open(ME, c.id(), "질문", KEY);
        ConversationTurnService.OpenedTurn b = serverB.open(ME, c.id(), "질문", KEY);
        ConversationDtos.Saved savedA = run(serverA, a, new ArrayList<>());
        ConversationDtos.Saved savedB = run(serverB, b, new ArrayList<>());

        assertThat(a.isReplay()).isFalse();
        assertThat(b.isReplay()).isFalse();
        assertThat(savedB).isEqualTo(savedA);
        assertThat(store.messagesOf(c.id())).hasSize(2);
        assertThat(store.conversation(c.id()).orElseThrow().messageCount()).isEqualTo(2);
        assertThat(insertPairs(store)).isEqualTo(1);
    }

    @Test
    void losingRequestWithADifferentModelAnswerGetsTheStoredAnswerInSaved() {
        FakeConversationStore store = new FakeConversationStore();
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);
        when(tutor.openChat(anyLong(), any())).thenReturn(opened);
        AtomicInteger calls = new AtomicInteger();
        scriptTutor(tutor, (request, onDelta) -> onDelta.accept(calls.incrementAndGet() == 1 ? "첫 답" : "둘째 답"));
        ConversationTurnService serverA = turnsOn(store);
        ConversationTurnService serverB = turnsOn(store);

        ConversationTurnService.OpenedTurn a = serverA.open(ME, c.id(), "질문", KEY);
        ConversationTurnService.OpenedTurn b = serverB.open(ME, c.id(), "질문", KEY);
        ConversationDtos.Saved savedA = run(serverA, a, new ArrayList<>());
        List<String> streamedB = new ArrayList<>();
        ConversationDtos.Saved savedB = run(serverB, b, streamedB);

        assertThat(streamedB).containsExactly("둘째 답");
        assertThat(savedA.answer()).isNull();
        assertThat(savedA.replaced()).isNull();
        assertThat(savedB.userMessageId()).isEqualTo(savedA.userMessageId());
        assertThat(savedB.assistantMessageId()).isEqualTo(savedA.assistantMessageId());
        assertThat(savedB.answer()).isEqualTo("첫 답");
        assertThat(savedB.replaced()).isTrue();
        assertThat(store.messagesOf(c.id())).extracting(ConversationStore.Message::content)
                .containsExactly("질문", "첫 답");
        assertThat(store.conversation(c.id()).orElseThrow().messageCount()).isEqualTo(2);
    }

    @Test
    void duplicateInsertWithADifferentModelAnswerAlsoSendsTheStoredAnswer() {
        AtomicInteger hidden = new AtomicInteger(4);
        FakeConversationStore store = new FakeConversationStore() {
            @Override
            public synchronized Optional<SavedPair> findPairByClientMessageId(long conversationId, UUID key) {
                Optional<SavedPair> real = super.findPairByClientMessageId(conversationId, key);
                return hidden.getAndDecrement() > 0 ? Optional.empty() : real;
            }
        };
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);
        when(tutor.openChat(anyLong(), any())).thenReturn(opened);
        AtomicInteger calls = new AtomicInteger();
        scriptTutor(tutor, (request, onDelta) -> onDelta.accept(calls.incrementAndGet() == 1 ? "첫 답" : "둘째 답"));
        ConversationTurnService serverA = turnsOn(store);
        ConversationTurnService serverB = turnsOn(store);

        ConversationTurnService.OpenedTurn a = serverA.open(ME, c.id(), "질문", KEY);
        ConversationTurnService.OpenedTurn b = serverB.open(ME, c.id(), "질문", KEY);
        run(serverA, a, new ArrayList<>());
        ConversationDtos.Saved savedB = run(serverB, b, new ArrayList<>());

        assertThat(savedB.answer()).isEqualTo("첫 답");
        assertThat(savedB.replaced()).isTrue();
        assertThat(store.messagesOf(c.id())).extracting(ConversationStore.Message::content)
                .containsExactly("질문", "첫 답");
    }

    @Test
    void savedJsonCarriesAnswerOnlyWhenReplaced() {
        var plain = JSON.readTree(JSON.writeValueAsString(new ConversationDtos.Saved("1", "2")));
        var replaced = JSON.readTree(JSON.writeValueAsString(new ConversationDtos.Saved("1", "2", "첫 답", true)));

        assertThat(plain.has("answer")).isFalse();
        assertThat(plain.has("replaced")).isFalse();
        assertThat(replaced.get("answer").asString()).isEqualTo("첫 답");
        assertThat(replaced.get("replaced").asBoolean()).isTrue();
    }

    @Test
    void duplicateKeyOnInsertIsTreatedAsAlreadySaved() {
        // Hides the keyed pair from the first four lookups (both opens and both pre-insert checks), so the second
        // save reaches the insert and trips the partial UNIQUE index, like a check that missed a concurrent commit.
        AtomicInteger hidden = new AtomicInteger(4);
        FakeConversationStore store = new FakeConversationStore() {
            @Override
            public synchronized Optional<SavedPair> findPairByClientMessageId(long conversationId, UUID key) {
                Optional<SavedPair> real = super.findPairByClientMessageId(conversationId, key);
                return hidden.getAndDecrement() > 0 ? Optional.empty() : real;
            }
        };
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);
        when(tutor.openChat(anyLong(), any())).thenReturn(opened);
        scriptTutor(tutor, (request, onDelta) -> onDelta.accept("답"));
        ConversationTurnService serverA = turnsOn(store);
        ConversationTurnService serverB = turnsOn(store);

        ConversationTurnService.OpenedTurn a = serverA.open(ME, c.id(), "질문", KEY);
        ConversationTurnService.OpenedTurn b = serverB.open(ME, c.id(), "질문", KEY);
        ConversationDtos.Saved savedA = run(serverA, a, new ArrayList<>());
        ConversationDtos.Saved savedB = run(serverB, b, new ArrayList<>());

        assertThat(savedB).isEqualTo(savedA);
        assertThat(insertPairs(store)).isEqualTo(2);           // the second insert was rejected by the index
        assertThat(store.messagesOf(c.id())).hasSize(2);
        assertThat(store.conversation(c.id()).orElseThrow().messageCount()).isEqualTo(2);
    }

    /** Saved by the other request, then deleted before the recovery lookup: the defined 410, not 404. */
    @Test
    void duplicateKeyRecoveryOnADeletedConversationIsTheDeletedWhileStreaming410() {
        AtomicInteger hidden = new AtomicInteger(4);
        FakeConversationStore store = new FakeConversationStore() {
            @Override
            public synchronized Optional<SavedPair> findPairByClientMessageId(long conversationId, UUID key) {
                Optional<SavedPair> real = super.findPairByClientMessageId(conversationId, key);
                return hidden.getAndDecrement() > 0 ? Optional.empty() : real;
            }

            @Override
            public synchronized SavedPair insertPair(long conversationId, String question, String answer,
                                                     String title, Instant now, UUID clientMessageId) {
                try {
                    return super.insertPair(conversationId, question, answer, title, now, clientMessageId);
                } catch (DuplicateKeyException e) {
                    softDeleteConversation(conversationId, now);
                    throw e;
                }
            }
        };
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);
        when(tutor.openChat(anyLong(), any())).thenReturn(opened);
        scriptTutor(tutor, (request, onDelta) -> onDelta.accept("답"));
        ConversationTurnService serverA = turnsOn(store);
        ConversationTurnService serverB = turnsOn(store);

        ConversationTurnService.OpenedTurn a = serverA.open(ME, c.id(), "질문", KEY);
        ConversationTurnService.OpenedTurn b = serverB.open(ME, c.id(), "질문", KEY);
        run(serverA, a, new ArrayList<>());

        assertThatThrownBy(() -> run(serverB, b, new ArrayList<>()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.GONE);
                    assertThat(e.getMessage()).isEqualTo(ConversationService.DELETED_WHILE_STREAMING);
                });
        assertThat(store.messagesOf(c.id())).hasSize(2);
    }

    @Test
    void concurrentRequestWithADifferentTextUnderTheSameKeyEndsWith409AndStoresNothingMore() {
        FakeConversationStore store = new FakeConversationStore();
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);
        when(tutor.openChat(anyLong(), any())).thenReturn(opened);
        scriptTutor(tutor, (request, onDelta) -> onDelta.accept("답"));
        ConversationTurnService serverA = turnsOn(store);
        ConversationTurnService serverB = turnsOn(store);

        ConversationTurnService.OpenedTurn a = serverA.open(ME, c.id(), "질문", KEY);
        ConversationTurnService.OpenedTurn b = serverB.open(ME, c.id(), "다른 질문", KEY);
        run(serverA, a, new ArrayList<>());

        assertThatThrownBy(() -> run(serverB, b, new ArrayList<>()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(store.messagesOf(c.id())).extracting(ConversationStore.Message::content)
                .containsExactly("질문", "답");
    }

    @Test
    void newTurnStillReleasesItsReservationAndSummarizes() {
        FakeConversationStore store = new FakeConversationStore();
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);
        when(tutor.openChat(anyLong(), any())).thenReturn(opened);
        scriptTutor(tutor, (request, onDelta) -> onDelta.accept("답"));
        ConversationTurnService turns = turnsOn(store);

        ConversationTurnService.OpenedTurn turn = turns.open(ME, c.id(), "질문", KEY);
        run(turns, turn, new ArrayList<>());
        turns.finish(turn, true);

        verify(opened, times(1)).release();
        verify(summarizer).summarizeIfNeeded(c.id());
        assertThat(store.clientMessageIdOf(store.messagesOf(c.id()).get(0).id())).contains(KEY);

        clearInvocations(tutor, opened, summarizer);
        ConversationTurnService.OpenedTurn replay = turns.open(ME, c.id(), "질문", KEY);
        turns.finish(replay, true);
        verify(opened, never()).release();
        verifyNoInteractions(summarizer);
    }
}
