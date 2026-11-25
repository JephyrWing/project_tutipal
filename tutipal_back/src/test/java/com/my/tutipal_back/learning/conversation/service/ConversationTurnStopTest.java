package com.my.tutipal_back.learning.conversation.service;

import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.NOW;
import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.scriptTutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.my.tutipal_back.ai.quota.MutableClock;
import com.my.tutipal_back.learning.conversation.dto.ConversationDtos;
import com.my.tutipal_back.learning.conversation.repository.ConversationStore;
import com.my.tutipal_back.learning.conversation.repository.FakeConversationStore;
import com.my.tutipal_back.learning.tutor.service.OpenedChat;
import com.my.tutipal_back.learning.tutor.service.TutorService;
import com.my.tutipal_back.sessions.service.StudySessionService;

/**
 * PT-106 Codex round 2 (developer test): a stop or disconnect right after the last delta trips nothing inside
 * tutor.chat, so the turn re-checks the heartbeat before saving. Stopped → no pair, no saved, no summary.
 */
class ConversationTurnStopTest {

    private final FakeConversationStore store = new FakeConversationStore();
    private final StudySessionService sessions = mock(StudySessionService.class);
    private final TutorService tutor = mock(TutorService.class);
    private final ConversationSummarizer summarizer = mock(ConversationSummarizer.class);
    private final ConversationTurnService turns = new ConversationTurnService(
            new ConversationService(store, sessions, new MutableClock(NOW)), tutor, sessions,
            new ConversationBinding(), summarizer);

    @Test
    void stopRightAfterTheLastDeltaSavesNothing() {
        when(tutor.openChat(anyLong(), any())).thenReturn(mock(OpenedChat.class));
        AtomicBoolean gone = new AtomicBoolean(false);
        scriptTutor(tutor, (request, onDelta) -> {
            onDelta.accept("답");
            gone.set(true);              // the screen stops after the last delta; no further event follows
        });
        ConversationStore.Conversation c = store.seedConversation(2L, null, "수학", NOW);
        ConversationTurnService.OpenedTurn turn = turns.open(2L, c.id(), "질문");
        RuntimeException stopped = new IllegalStateException("client gone");

        assertThatThrownBy(() -> turns.run(turn, sources -> { }, delta -> { }, event -> { }, () -> {
            if (gone.get()) {
                throw stopped;
            }
        })).isSameAs(stopped);
        turns.finish(turn, false);

        assertThat(store.messagesOf(c.id())).isEmpty();
        assertThat(store.calls).noneMatch(call -> call.startsWith("insertPair"));
        verifyNoInteractions(summarizer);
    }

    @Test
    void aTurnStillConnectedAfterTheLastDeltaIsSaved() {
        when(tutor.openChat(anyLong(), any())).thenReturn(mock(OpenedChat.class));
        scriptTutor(tutor, (request, onDelta) -> onDelta.accept("답"));
        ConversationStore.Conversation c = store.seedConversation(2L, null, "수학", NOW);
        ConversationTurnService.OpenedTurn turn = turns.open(2L, c.id(), "질문");

        ConversationDtos.Saved saved = turns.run(turn, sources -> { }, delta -> { }, event -> { }, () -> { });
        turns.finish(turn, true);

        assertThat(store.messagesOf(c.id())).extracting(ConversationStore.Message::content).containsExactly("질문", "답");
        assertThat(saved.assistantMessageId()).isEqualTo(String.valueOf(store.messagesOf(c.id()).get(1).id()));
        verify(summarizer).summarizeIfNeeded(c.id());
    }
}
