package com.my.tutipal_back.learning.conversation.controller;

import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.JSON;
import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.NOW;
import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.login;
import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.logout;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.my.tutipal_back.ai.quota.MutableClock;
import com.my.tutipal_back.learning.conversation.ConversationTestSupport;
import com.my.tutipal_back.learning.conversation.repository.ConversationStore;
import com.my.tutipal_back.learning.conversation.repository.FakeConversationStore;
import com.my.tutipal_back.learning.conversation.service.ConversationService;
import com.my.tutipal_back.sessions.service.StudySessionService;

import tools.jackson.databind.JsonNode;

/**
 * PT-13 decision C: PUT /api/conversations/{id}/saved {"saved": true|false}. Owner only: someone else's, missing or
 * deleted conversation is 404 and nothing changes. saved / savedAt appear in the list and the detail.
 */
class ConversationSaveToggleWebTest {

    private static final long ME = 2L;
    private static final long OTHER = 3L;
    private static final long ENDED_SESSION = 10L;

    private final FakeConversationStore store = new FakeConversationStore();
    private final MutableClock clock = new MutableClock(NOW);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        login(ME);
        mvc = ConversationTestSupport.mvc(new ConversationController(
                new ConversationService(store, mock(StudySessionService.class), clock)));
    }

    @AfterEach
    void tearDown() {
        logout();
    }

    private ResultActions saved(long id, String body) throws Exception {
        return mvc.perform(put("/api/conversations/{id}/saved", id).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static JsonNode body(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void ownerSavesAndTheListAndDetailShowIt() throws Exception {
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW.minus(Duration.ofDays(1)));

        JsonNode state = body(saved(c.id(), "{\"saved\":true}").andExpect(status().isOk()));

        assertThat(state.get("id").asString()).isEqualTo(String.valueOf(c.id()));
        assertThat(state.get("saved").asBoolean()).isTrue();
        assertThat(state.get("savedAt").asString()).isEqualTo(NOW.toString());
        JsonNode item = body(mvc.perform(get("/api/conversations"))).get("items").get(0);
        assertThat(item.get("saved").asBoolean()).isTrue();
        assertThat(item.get("savedAt").asString()).isEqualTo(NOW.toString());
        JsonNode detail = body(mvc.perform(get("/api/conversations/{id}", c.id())));
        assertThat(detail.get("saved").asBoolean()).isTrue();
        assertThat(detail.get("savedAt").asString()).isEqualTo(NOW.toString());
    }

    @Test
    void unsavedConversationsShowSavedFalse() throws Exception {
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);

        JsonNode detail = body(mvc.perform(get("/api/conversations/{id}", c.id())).andExpect(status().isOk()));

        assertThat(detail.get("saved").asBoolean()).isFalse();
        assertThat(detail.get("savedAt") == null || detail.get("savedAt").isNull()).isTrue();
    }

    @Test
    void savingAgainKeepsTheFirstTimeAndUnsavingClearsIt() throws Exception {
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);
        saved(c.id(), "{\"saved\":true}").andExpect(status().isOk());
        clock.set(NOW.plus(Duration.ofHours(3)));

        JsonNode again = body(saved(c.id(), "{\"saved\":true}").andExpect(status().isOk()));
        assertThat(again.get("savedAt").asString()).isEqualTo(NOW.toString());

        JsonNode off = body(saved(c.id(), "{\"saved\":false}").andExpect(status().isOk()));
        assertThat(off.get("saved").asBoolean()).isFalse();
        assertThat(off.get("savedAt").isNull()).isTrue();
        assertThat(store.conversation(c.id()).orElseThrow().savedAt()).isNull();
    }

    /** Saving a conversation does not move its last activity (it is not a turn). */
    @Test
    void savingDoesNotTouchLastMessageAt() throws Exception {
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW.minus(Duration.ofDays(20)));

        saved(c.id(), "{\"saved\":true}").andExpect(status().isOk());

        assertThat(store.conversation(c.id()).orElseThrow().lastMessageAt()).isEqualTo(NOW.minus(Duration.ofDays(20)));
    }

    /** A class conversation of an ended session can still be saved (that is when retention matters). */
    @Test
    void classConversationOfAnEndedSessionCanBeSaved() throws Exception {
        store.seedSession(ENDED_SESSION, NOW.minus(Duration.ofDays(1)), 7);
        ConversationStore.Conversation c = store.seedConversation(ME, ENDED_SESSION, "자바", NOW);

        saved(c.id(), "{\"saved\":true}").andExpect(status().isOk());

        assertThat(store.conversation(c.id()).orElseThrow().saved()).isTrue();
    }

    @Test
    void someoneElsesConversationIs404AndUnchanged() throws Exception {
        ConversationStore.Conversation theirs = store.seedConversation(OTHER, null, "수학", NOW);
        String before = store.snapshot();

        saved(theirs.id(), "{\"saved\":true}").andExpect(status().isNotFound());

        assertThat(store.snapshot()).isEqualTo(before);
        assertThat(store.calls).noneMatch(call -> call.startsWith("setSaved"));
    }

    @Test
    void deletedOrMissingConversationIs404() throws Exception {
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);
        store.softDeleteConversation(c.id(), NOW);

        saved(c.id(), "{\"saved\":true}").andExpect(status().isNotFound());
        saved(999L, "{\"saved\":true}").andExpect(status().isNotFound());

        assertThat(store.conversation(c.id()).orElseThrow().savedAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"saved\":null}"})
    void missingSavedValueIs400(String body) throws Exception {
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);

        saved(c.id(), body).andExpect(status().isBadRequest());

        assertThat(store.calls).noneMatch(call -> call.startsWith("setSaved"));
    }
}
