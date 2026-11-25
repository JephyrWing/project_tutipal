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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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

/** PT-106 (developer test): a turn that completes without any answer text saves nothing (V21 needs 1..32000). */
class ConversationTurnEmptyAnswerTest {

    private static final long ME = 2L;

    private final FakeConversationStore store = new FakeConversationStore();
    private final TutorService tutor = mock(TutorService.class);
    private final ConversationSummarizer summarizer = mock(ConversationSummarizer.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        login(ME);
        StudySessionService sessions = mock(StudySessionService.class);
        when(tutor.openChat(anyLong(), any())).thenReturn(mock(OpenedChat.class));
        ConversationService conversations = new ConversationService(store, sessions, new MutableClock(NOW));
        mvc = ConversationTestSupport.mvc(new ConversationTurnController(new ConversationTurnService(conversations,
                tutor, sessions, new ConversationBinding(), summarizer)));
    }

    @AfterEach
    void tearDown() {
        logout();
    }

    private String stream(long conversationId) throws Exception {
        MvcResult result = mvc.perform(post("/api/conversations/{id}/messages", conversationId)
                        .accept(MediaType.TEXT_EVENT_STREAM).contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("content", "질문"))))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(5_000);
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void blankAnswerEndsWithErrorAndSavesNothing() throws Exception {
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);
        scriptTutor(tutor, (request, onDelta) -> onDelta.accept("   "));

        String body = stream(c.id());

        assertThat(eventNames(body)).doesNotContain("saved", "done").endsWith("error");
        assertThat(event(body, "error").json().get("message").asString()).isNotBlank();
        assertThat(store.messagesOf(c.id())).isEmpty();
        verify(summarizer, after(300).never()).summarizeIfNeeded(anyLong());
    }

    @Test
    void questionIsStoredStripped() throws Exception {
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);
        scriptTutor(tutor, (request, onDelta) -> onDelta.accept("답"));

        mvc.perform(post("/api/conversations/{id}/messages", c.id())
                        .accept(MediaType.TEXT_EVENT_STREAM).contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("content", "  앞뒤 공백  "))))
                .andExpect(request().asyncStarted())
                .andReturn().getAsyncResult(5_000);

        assertThat(store.messagesOf(c.id()).getFirst().content()).isEqualTo("앞뒤 공백");
        assertThat(store.conversation(c.id()).orElseThrow().title()).isEqualTo("앞뒤 공백");
    }
}
