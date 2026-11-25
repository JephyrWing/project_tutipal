package com.my.tutipal_back.learning.conversation.controller;

import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.JSON;
import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.NOW;
import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.event;
import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.eventNames;
import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.login;
import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.logout;
import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.scriptTutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.my.tutipal_back.ai.quota.MutableClock;
import com.my.tutipal_back.learning.conversation.ConversationTestSupport;
import com.my.tutipal_back.learning.conversation.repository.ConversationStore;
import com.my.tutipal_back.learning.conversation.repository.FakeConversationStore;
import com.my.tutipal_back.learning.conversation.service.ConversationBinding;
import com.my.tutipal_back.learning.conversation.service.ConversationService;
import com.my.tutipal_back.learning.conversation.service.ConversationSummarizer;
import com.my.tutipal_back.learning.conversation.service.ConversationTurnService;
import com.my.tutipal_back.learning.tutor.service.OpenedChat;
import com.my.tutipal_back.learning.tutor.service.TutorService;
import com.my.tutipal_back.sessions.service.StudySessionService;

/**
 * PT-106 Codex re-review (developer test): the pair was committed but the saved event was lost, so the screen
 * resends the same question with the same clientMessageId. Same text → the stored answer is replayed over SSE
 * (delta → saved → done) with no openChat / chat (no quota, no model); different text → 409 JSON; someone else's
 * conversation is still 404 before the key is looked up.
 */
class ConversationTurnIdempotencyWebTest {

    private static final long ME = 2L;
    private static final long OTHER = 3L;
    private static final UUID KEY = UUID.fromString("0b6f7a43-3f0e-4b8e-9a59-2d7c3c1f4e11");

    private final FakeConversationStore store = new FakeConversationStore();
    private final StudySessionService sessions = mock(StudySessionService.class);
    private final TutorService tutor = mock(TutorService.class);
    private final OpenedChat opened = mock(OpenedChat.class);
    private final ConversationSummarizer summarizer = mock(ConversationSummarizer.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        login(ME);
        when(tutor.openChat(anyLong(), any())).thenReturn(opened);
        scriptTutor(tutor, (request, onDelta) -> {
            onDelta.accept("저장된 ");
            onDelta.accept("답변");
        });
        ConversationService conversations = new ConversationService(store, sessions, new MutableClock(NOW));
        ConversationTurnService turns = new ConversationTurnService(conversations, tutor, sessions,
                new ConversationBinding(), summarizer);
        mvc = ConversationTestSupport.mvc(new ConversationTurnController(turns));
    }

    @AfterEach
    void tearDown() {
        logout();
    }

    private ResultActions send(long conversationId, String text, Object key) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", text);
        if (key != null) {
            body.put("clientMessageId", key.toString());
        }
        return mvc.perform(post("/api/conversations/{id}/messages", conversationId)
                .accept(MediaType.TEXT_EVENT_STREAM).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
    }

    private String stream(long conversationId, String text, Object key) throws Exception {
        MvcResult result = send(conversationId, text, key).andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(5_000);
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * The turn guard is freed after the emitter completes (then the summary runs on the same thread), so a second
     * turn sent right after the stream may still get 429. The n-th summary call means the n-th saved turn freed it.
     */
    private void awaitTurnReleased(long conversationId, int savedTurns) {
        verify(summarizer, timeout(5_000).times(savedTurns)).summarizeIfNeeded(conversationId);
    }

    private long insertPairs() {
        return store.calls.stream().filter(call -> call.startsWith("insertPair:")).count();
    }

    @Test
    void resendWithSameKeyAndTextReplaysTheStoredAnswerWithoutModelOrQuota() throws Exception {
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);
        String first = stream(c.id(), "미분이 뭐예요?", KEY);
        var firstIds = event(first, "saved").json();
        awaitTurnReleased(c.id(), 1);
        clearInvocations(tutor, opened, summarizer);

        String again = stream(c.id(), "미분이 뭐예요?", KEY);

        assertThat(eventNames(again)).containsExactly("delta", "saved", "done");
        assertThat(event(again, "delta").json().get("t").asString()).isEqualTo("저장된 답변");
        var ids = event(again, "saved").json();
        assertThat(ids.get("userMessageId").asString()).isEqualTo(firstIds.get("userMessageId").asString());
        assertThat(ids.get("assistantMessageId").asString()).isEqualTo(firstIds.get("assistantMessageId").asString());
        assertThat(firstIds.has("answer")).isFalse();
        assertThat(ids.has("answer")).isFalse();
        verify(tutor, never()).openChat(anyLong(), any());
        verifyNoInteractions(tutor, opened);
        assertThat(store.messagesOf(c.id())).hasSize(2);
        assertThat(store.conversation(c.id()).orElseThrow().messageCount()).isEqualTo(2);
        assertThat(insertPairs()).isEqualTo(1);
        verify(summarizer, after(200).never()).summarizeIfNeeded(anyLong());
    }

    @Test
    void keyIsStoredOnTheQuestionRowOnly() throws Exception {
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);

        stream(c.id(), "질문", KEY);

        List<ConversationStore.Message> saved = store.messagesOf(c.id());
        assertThat(store.clientMessageIdOf(saved.get(0).id())).contains(KEY);
        assertThat(store.clientMessageIdOf(saved.get(1).id())).isEmpty();
    }

    @Test
    void resendIsComparedAfterTrimmingLikeTheSavedQuestion() throws Exception {
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);
        stream(c.id(), "  질문  ", KEY);
        awaitTurnReleased(c.id(), 1);
        clearInvocations(tutor);

        String again = stream(c.id(), "질문", KEY);

        assertThat(eventNames(again)).containsExactly("delta", "saved", "done");
        verifyNoInteractions(tutor);
        assertThat(store.messagesOf(c.id())).hasSize(2);
    }

    @Test
    void sameKeyWithDifferentTextIs409JsonAndCallsNothing() throws Exception {
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);
        stream(c.id(), "첫 질문", KEY);
        awaitTurnReleased(c.id(), 1);
        clearInvocations(tutor);

        send(c.id(), "고친 질문", KEY)
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").isNotEmpty());

        verifyNoInteractions(tutor);
        assertThat(store.messagesOf(c.id())).hasSize(2);
        // the turn slot was released: a new key works right away
        assertThat(eventNames(stream(c.id(), "고친 질문", UUID.randomUUID()))).endsWith("saved", "done");
    }

    @Test
    void someoneElsesConversationIs404BeforeTheKeyIsLookedUp() throws Exception {
        ConversationStore.Conversation theirs = store.seedConversation(OTHER, null, "수학", NOW);
        store.insertPair(theirs.id(), "남의 질문", "남의 답", "남의 질문", NOW, KEY);
        store.calls.clear();

        send(theirs.id(), "남의 질문", KEY)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        assertThat(store.calls).noneMatch(call -> call.startsWith("findPairByClientMessageId"));
        verifyNoInteractions(tutor);
    }

    /** Soft delete: the stored pair of a deleted conversation is never replayed (404 like a missing one). */
    @Test
    void resendIntoADeletedConversationIs404AndReplaysNothing() throws Exception {
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);
        store.insertPair(c.id(), "미분이 뭐예요?", "저장된 답변", "미분이 뭐예요?", NOW, KEY);
        store.softDeleteConversation(c.id(), NOW);

        MvcResult result = send(c.id(), "미분이 뭐예요?", KEY)
                .andExpect(status().isNotFound())
                .andExpect(request().asyncNotStarted())
                .andExpect(jsonPath("$.status").value(404))
                .andReturn();

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("저장된 답변");
        verifyNoInteractions(tutor);
        assertThat(insertPairs()).isEqualTo(1);
    }

    @Test
    void newKeyForTheSameTextIsANewTurn() throws Exception {
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);

        stream(c.id(), "질문", KEY);
        awaitTurnReleased(c.id(), 1);
        stream(c.id(), "질문", UUID.randomUUID());

        assertThat(store.messagesOf(c.id())).hasSize(4);
        assertThat(insertPairs()).isEqualTo(2);
    }

    @Test
    void requestWithoutKeyStillWorksAndIsNeverDeduplicated() throws Exception {
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);

        stream(c.id(), "질문", null);
        awaitTurnReleased(c.id(), 1);
        stream(c.id(), "질문", null);

        assertThat(store.messagesOf(c.id())).hasSize(4);
        assertThat(store.calls).noneMatch(call -> call.startsWith("findPairByClientMessageId"));
    }

    @Test
    void malformedKeyIs400Json() throws Exception {
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);

        send(c.id(), "질문", "not-a-uuid")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verifyNoInteractions(tutor);
    }
}
