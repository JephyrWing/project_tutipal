package com.my.tutipal_back.learning.conversation.controller;

import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.JSON;
import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.NOW;
import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.chatRequests;
import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.event;
import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.eventNames;
import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.login;
import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.logout;
import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.scriptTutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.my.tutipal_back.ai.client.AiUnavailableException;
import com.my.tutipal_back.ai.quota.AiFeature;
import com.my.tutipal_back.ai.quota.AiQuotaExceededException;
import com.my.tutipal_back.ai.quota.MutableClock;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.learning.conversation.ConversationTestSupport;
import com.my.tutipal_back.learning.conversation.repository.ConversationStore;
import com.my.tutipal_back.learning.conversation.repository.FakeConversationStore;
import com.my.tutipal_back.learning.conversation.service.ConversationBinding;
import com.my.tutipal_back.learning.conversation.service.ConversationService;
import com.my.tutipal_back.learning.conversation.service.ConversationSummarizer;
import com.my.tutipal_back.learning.conversation.service.ConversationTurnService;
import com.my.tutipal_back.learning.tutor.dto.ChatRequest;
import com.my.tutipal_back.learning.tutor.service.OpenedChat;
import com.my.tutipal_back.learning.tutor.service.TutorService;
import com.my.tutipal_back.sessions.dto.ChatSettings;
import com.my.tutipal_back.sessions.entity.SessionRole;
import com.my.tutipal_back.sessions.service.StudySessionService;

/**
 * PT-106 POST /api/conversations/{id}/messages (SSE) with TutorService mocked:
 * request built from stored history (AC-1), isolation (AC-6), session checks before openChat (AC-8),
 * delete during streaming (AC-12), validation / turn guard / quota (AC-15), failure saves nothing (AC-16),
 * pair save + saved event (AC-17), long conversation cap (AC-19), summary after the turn (AC-21).
 */
class ConversationTurnWebTest {

    private static final long ME = 2L;
    private static final long OTHER = 3L;
    private static final long SESSION = 10L;
    private static final String DELETED_MESSAGE = "대화가 삭제되어 저장하지 않았어요";

    private final FakeConversationStore store = new FakeConversationStore();
    private final StudySessionService sessions = mock(StudySessionService.class);
    private final TutorService tutor = mock(TutorService.class);
    private final OpenedChat opened = mock(OpenedChat.class);
    private final ConversationSummarizer summarizer = mock(ConversationSummarizer.class);
    private final MutableClock clock = new MutableClock(NOW);
    private ConversationTurnService turns;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        login(ME);
        when(sessions.requireActiveRole(ME, SESSION)).thenReturn(SessionRole.STUDENT);
        when(sessions.chatSettings(ME, SESSION)).thenReturn(new ChatSettings("파이썬", 2, true, true));
        when(tutor.openChat(anyLong(), any())).thenReturn(opened);
        scriptTutor(tutor, (request, onDelta) -> onDelta.accept("답"));
        ConversationService conversations = new ConversationService(store, sessions, clock);
        turns = new ConversationTurnService(conversations, tutor, sessions, new ConversationBinding(), summarizer);
        mvc = ConversationTestSupport.mvc(new ConversationTurnController(turns));
    }

    @AfterEach
    void tearDown() {
        logout();
    }

    private ResultActions send(long conversationId, String content) throws Exception {
        return mvc.perform(post("/api/conversations/{id}/messages", conversationId)
                .accept(MediaType.TEXT_EVENT_STREAM).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("content", content))));
    }

    private MvcResult start(long conversationId, String content) throws Exception {
        return send(conversationId, content).andExpect(request().asyncStarted()).andReturn();
    }

    private static String finish(MvcResult result) throws Exception {
        result.getAsyncResult(5_000);
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String stream(long conversationId, String content) throws Exception {
        return finish(start(conversationId, content));
    }

    private ConversationStore.Conversation personal() {
        return store.seedConversation(ME, null, "수학", NOW);
    }

    // ------------------------------------------------------------------ AC-1 request from stored history

    @Test
    void chatRequestIsLast39StoredMessagesAfterActiveSummaryPlusQuestion() throws Exception {
        ConversationStore.Conversation c = personal();
        List<ConversationStore.Message> stored = new java.util.ArrayList<>(store.seedMessages(c.id(), 59, NOW));
        stored.add(store.seedMessage(c.id(), "assistant", "x".repeat(32000), NOW));
        store.seedSummary(c.id(), "앞부분 요약", stored.get(0).id(), stored.get(9).id(), NOW);

        String body = stream(c.id(), "새 질문");

        assertThat(eventNames(body)).endsWith("done");
        ChatRequest request = chatRequests(tutor).getFirst();
        assertThat(request.messages()).hasSize(40);
        // messages 11..60 come after the summary; the newest 39 of them are 22..60
        List<ConversationStore.Message> expected = stored.subList(21, 60);
        for (int i = 0; i < 39; i++) {
            assertThat(request.messages().get(i).role()).isEqualTo(expected.get(i).role());
        }
        assertThat(request.messages().getFirst().content()).isEqualTo(expected.getFirst().content());
        assertThat(request.messages().get(38).content()).isEqualTo("x".repeat(8000));
        assertThat(request.messages().getLast()).isEqualTo(new ChatRequest.Message("user", "새 질문"));
        assertThat(request.topic()).isEqualTo("수학");
        assertThat(request.sessionId()).isNull();
        assertThat(request.variantQuizId()).isNull();
        assertThat(request.screenSummary()).isNull();
    }

    @Test
    void withoutSummaryAllStoredMessagesUpTo39AreUsed() throws Exception {
        ConversationStore.Conversation c = personal();
        List<ConversationStore.Message> stored = store.seedMessages(c.id(), 4, NOW);

        stream(c.id(), "다음 질문");

        ChatRequest request = chatRequests(tutor).getFirst();
        assertThat(request.messages()).extracting(ChatRequest.Message::content)
                .containsExactly(stored.get(0).content(), stored.get(1).content(), stored.get(2).content(),
                        stored.get(3).content(), "다음 질문");
    }

    @Test
    void sessionConversationSendsItsSessionIdAndTopic() throws Exception {
        ConversationStore.Conversation c = store.seedConversation(ME, SESSION, "파이썬", NOW);

        stream(c.id(), "range가 뭐예요?");

        ChatRequest request = chatRequests(tutor).getFirst();
        assertThat(request.sessionId()).isEqualTo(SESSION);
        assertThat(request.topic()).isEqualTo("파이썬");
        assertThat(request.variantQuizId()).isNull();
        assertThat(request.screenSummary()).isNull();
    }

    // ------------------------------------------------------------------ AC-19 long conversation

    @Test
    void longConversationStillSendsAtMost40Messages() throws Exception {
        ConversationStore.Conversation c = personal();
        List<ConversationStore.Message> stored = store.seedMessages(c.id(), 120, NOW);
        store.seedSummary(c.id(), "요약", stored.get(0).id(), stored.get(59).id(), NOW);

        stream(c.id(), "질문");

        ChatRequest request = chatRequests(tutor).getFirst();
        assertThat(request.messages()).hasSize(40);
        assertThat(request.messages().getFirst().content()).isEqualTo(stored.get(81).content());
    }

    // ------------------------------------------------------------------ AC-17 success

    @Test
    void successSavesTheQuestionAndTheWholeAnswerThenSendsSavedThenDone() throws Exception {
        ConversationStore.Conversation c = personal();
        clock.set(NOW.plus(Duration.ofHours(1)));
        scriptTutor(tutor, (request, onDelta) -> {
            onDelta.accept("안녕");
            onDelta.accept("하세요");
        });

        String body = stream(c.id(), "인사해 줘");

        assertThat(eventNames(body)).containsExactly("delta", "delta", "saved", "done");
        List<ConversationStore.Message> saved = store.messagesOf(c.id());
        assertThat(saved).extracting(ConversationStore.Message::role).containsExactly("user", "assistant");
        assertThat(saved).extracting(ConversationStore.Message::content).containsExactly("인사해 줘", "안녕하세요");
        assertThat(store.calls.stream().filter(call -> call.startsWith("insertPair:")).count()).isEqualTo(1);
        ConversationStore.Conversation after = store.conversation(c.id()).orElseThrow();
        assertThat(after.messageCount()).isEqualTo(2);
        assertThat(after.lastMessageAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
        var ids = event(body, "saved").json();
        assertThat(ids.get("userMessageId").asString()).isEqualTo(String.valueOf(saved.get(0).id()));
        assertThat(ids.get("assistantMessageId").asString()).isEqualTo(String.valueOf(saved.get(1).id()));
    }

    @Test
    void firstSaveSetsTitleToFirst40CharactersAndLaterTurnsKeepIt() throws Exception {
        ConversationStore.Conversation c = personal();

        stream(c.id(), "가".repeat(50));
        stream(c.id(), "두 번째 질문");

        assertThat(store.conversation(c.id()).orElseThrow().title()).isEqualTo("가".repeat(40));
    }

    @Test
    void assistantAnswerIsStoredUpTo32000Characters() throws Exception {
        ConversationStore.Conversation c = personal();
        scriptTutor(tutor, (request, onDelta) -> {
            for (int i = 0; i < 33; i++) {
                onDelta.accept("가".repeat(1000));
            }
        });

        stream(c.id(), "길게 설명해 줘");

        assertThat(store.messagesOf(c.id()).get(1).content()).hasSize(32000);
    }

    // ------------------------------------------------------------------ AC-16 failure saves nothing

    @Test
    void tutorFailureAfterSomeDeltasSavesNothingAndEndsWithSseError() throws Exception {
        ConversationStore.Conversation c = personal();
        scriptTutor(tutor, (request, onDelta) -> {
            onDelta.accept("부분 답");
            throw AiUnavailableException.afterOutput();
        });

        String body = stream(c.id(), "질문");

        assertThat(eventNames(body)).containsExactly("delta", "error");
        assertThat(event(body, "error").json().get("message").asString()).isEqualTo("AI 조교에 연결할 수 없어요");
        assertThat(store.messagesOf(c.id())).isEmpty();
        assertThat(store.conversation(c.id()).orElseThrow().messageCount()).isZero();
        assertThat(store.calls).noneMatch(call -> call.startsWith("insertPair"));
    }

    @Test
    void unexpectedFailureAlsoSavesNothing() throws Exception {
        ConversationStore.Conversation c = personal();
        scriptTutor(tutor, (request, onDelta) -> {
            throw new IllegalStateException("SECRET-INTERNAL");
        });

        String body = stream(c.id(), "질문");

        assertThat(eventNames(body)).containsExactly("error");
        assertThat(body).doesNotContain("SECRET-INTERNAL");
        assertThat(store.messagesOf(c.id())).isEmpty();
    }

    // ------------------------------------------------------------------ AC-12 deleted while streaming

    @Test
    void conversationDeletedWhileStreamingIsNotSavedNorRecreated() throws Exception {
        ConversationStore.Conversation c = personal();
        scriptTutor(tutor, (request, onDelta) -> {
            onDelta.accept("답");
            store.deleteConversation(c.id());
        });

        String body = stream(c.id(), "질문");

        assertThat(eventNames(body)).doesNotContain("saved", "done").endsWith("error");
        assertThat(event(body, "error").json().get("message").asString()).isEqualTo(DELETED_MESSAGE);
        assertThat(store.conversation(c.id())).isEmpty();
        assertThat(store.allMessages()).isEmpty();
        assertThat(store.calls).noneMatch(call -> call.equals("insertConversation"));
    }

    // ------------------------------------------------------------------ AC-6 another user's conversation

    @Test
    void continuingAnotherUsersConversationIs404BeforeAnyTutorCall() throws Exception {
        ConversationStore.Conversation theirs = store.seedConversation(OTHER, null, "수학", NOW);
        store.seedMessages(theirs.id(), 2, NOW);
        String before = store.snapshot();

        send(theirs.id(), "질문")
                .andExpect(status().isNotFound())
                .andExpect(request().asyncNotStarted())
                .andExpect(jsonPath("$.status").value(404));

        verifyNoInteractions(tutor);
        assertThat(store.snapshot()).isEqualTo(before);
    }

    @Test
    void missingConversationIs404() throws Exception {
        send(999L, "질문").andExpect(status().isNotFound()).andExpect(request().asyncNotStarted());

        verifyNoInteractions(tutor);
    }

    // ------------------------------------------------------------------ AC-8 session checks before openChat

    @ParameterizedTest
    @ValueSource(ints = {404, 410})
    void sessionConversationOfNonParticipantOrEndedSessionIsRefusedBeforeOpenChat(int code) throws Exception {
        ConversationStore.Conversation c = store.seedConversation(ME, SESSION, "파이썬", NOW);
        when(sessions.requireActiveRole(ME, SESSION)).thenThrow(new ApiException(HttpStatus.valueOf(code), "접근 불가"));

        send(c.id(), "질문").andExpect(status().is(code)).andExpect(request().asyncNotStarted())
                .andExpect(jsonPath("$.status").value(code));

        verify(tutor, never()).openChat(any(), any());
        assertThat(store.messagesOf(c.id())).isEmpty();
    }

    @Test
    void teacherCannotContinueSessionConversation() throws Exception {
        ConversationStore.Conversation c = store.seedConversation(ME, SESSION, "파이썬", NOW);
        when(sessions.requireActiveRole(ME, SESSION)).thenReturn(SessionRole.TEACHER);

        send(c.id(), "질문").andExpect(status().isForbidden()).andExpect(request().asyncNotStarted());

        verify(tutor, never()).openChat(any(), any());
    }

    // ------------------------------------------------------------------ AC-15 validation, guard, quota

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "8001"})
    void blankOrTooLongQuestionIs400BeforeQuota(String kind) throws Exception {
        ConversationStore.Conversation c = personal();
        String content = kind.equals("8001") ? "가".repeat(8001) : kind;

        send(c.id(), content).andExpect(status().isBadRequest()).andExpect(request().asyncNotStarted());

        verifyNoInteractions(tutor);
    }

    @Test
    void questionOf8000CharactersIsAccepted() throws Exception {
        ConversationStore.Conversation c = personal();

        String body = stream(c.id(), "가".repeat(8000));

        assertThat(eventNames(body)).endsWith("saved", "done");
    }

    @Test
    void secondTurnOfTheSameUserWhileOneIsRunningIs429AndTheGuardIsReleasedAfterwards() throws Exception {
        ConversationStore.Conversation first = personal();
        ConversationStore.Conversation second = personal();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        scriptTutor(tutor, (request, onDelta) -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            onDelta.accept("답");
        });

        MvcResult running = start(first.id(), "첫 질문");
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        send(second.id(), "동시 질문")
                .andExpect(status().isTooManyRequests())
                .andExpect(request().asyncNotStarted())
                .andExpect(jsonPath("$.status").value(429));

        release.countDown();
        assertThat(eventNames(finish(running))).endsWith("saved", "done");
        scriptTutor(tutor, (request, onDelta) -> onDelta.accept("다음 답"));
        assertThat(eventNames(stream(second.id(), "이제 질문"))).endsWith("saved", "done");
    }

    @Test
    void guardIsReleasedAfterAFailedTurn() throws Exception {
        ConversationStore.Conversation c = personal();
        scriptTutor(tutor, (request, onDelta) -> {
            throw new AiUnavailableException();
        });
        assertThat(eventNames(stream(c.id(), "질문"))).containsExactly("error");

        scriptTutor(tutor, (request, onDelta) -> onDelta.accept("답"));
        assertThat(eventNames(stream(c.id(), "다시 질문"))).endsWith("saved", "done");
    }

    @Test
    void guardIsReleasedWhenTheSessionCheckRejectsTheTurn() throws Exception {
        ConversationStore.Conversation ended = store.seedConversation(ME, SESSION, "파이썬", NOW);
        ConversationStore.Conversation c = personal();
        when(sessions.requireActiveRole(ME, SESSION)).thenThrow(new ApiException(HttpStatus.GONE, "끝난 수업"));

        send(ended.id(), "질문").andExpect(status().isGone());

        assertThat(eventNames(stream(c.id(), "개인 질문"))).endsWith("saved", "done");
    }

    @Test
    void quotaRejectionIsHttp429BeforeTheStreamAndReleasesTheGuard() throws Exception {
        ConversationStore.Conversation c = personal();
        when(tutor.openChat(anyLong(), any()))
                .thenThrow(new AiQuotaExceededException(AiFeature.CHAT, NOW.plusSeconds(3600), 3600))
                .thenReturn(opened);

        send(c.id(), "질문")
                .andExpect(status().isTooManyRequests())
                .andExpect(request().asyncNotStarted())
                .andExpect(jsonPath("$.feature").value("CHAT"));
        assertThat(store.messagesOf(c.id())).isEmpty();

        assertThat(eventNames(stream(c.id(), "다시 질문"))).endsWith("saved", "done");
    }

    // ------------------------------------------------------------------ AC-21 summary runs after the turn

    @Test
    void summaryRunsAfterDoneAndEmitterCompletionWithTheGuardReleased() throws Exception {
        ConversationStore.Conversation c = personal();
        ConversationStore.Conversation other = personal();
        CountDownLatch summaryEntered = new CountDownLatch(1);
        CountDownLatch summaryRelease = new CountDownLatch(1);
        doAnswer(inv -> {
            summaryEntered.countDown();
            summaryRelease.await(10, TimeUnit.SECONDS);
            return null;
        }).when(summarizer).summarizeIfNeeded(c.id());

        MvcResult result = start(c.id(), "질문");
        // completes while the summary is still blocked: the summary cannot be holding the response open
        String body = finish(result);
        assertThat(eventNames(body)).endsWith("saved", "done");
        assertThat(summaryEntered.await(5, TimeUnit.SECONDS)).isTrue();

        // and the per-user turn guard is already free while the summary runs
        doAnswer(inv -> null).when(summarizer).summarizeIfNeeded(other.id());
        assertThat(eventNames(stream(other.id(), "다른 질문"))).endsWith("saved", "done");
        summaryRelease.countDown();
    }

    @Test
    void summaryIsNotTriggeredWhenNothingWasSaved() throws Exception {
        ConversationStore.Conversation c = personal();
        scriptTutor(tutor, (request, onDelta) -> {
            throw new AiUnavailableException();
        });

        stream(c.id(), "질문");

        verify(summarizer, after(300).never()).summarizeIfNeeded(anyLong());
    }

    @Test
    void summaryIsTriggeredForTheConversationAfterASavedTurn() throws Exception {
        ConversationStore.Conversation c = personal();

        stream(c.id(), "질문");

        verify(summarizer, timeout(2_000)).summarizeIfNeeded(c.id());
    }

    @Test
    void lastMessageAtUsesTheServiceClock() throws Exception {
        ConversationStore.Conversation c = personal();
        Instant later = Instant.parse("2026-11-26T09:30:00Z");
        clock.set(later);

        stream(c.id(), "질문");

        assertThat(store.messagesOf(c.id())).allSatisfy(m -> assertThat(m.createdAt()).isEqualTo(later));
    }
}
