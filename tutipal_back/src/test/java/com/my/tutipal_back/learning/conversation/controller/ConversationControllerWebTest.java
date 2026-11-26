package com.my.tutipal_back.learning.conversation.controller;

import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.JSON;
import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.NOW;
import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.login;
import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.logout;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.my.tutipal_back.ai.dto.AgentDtos;
import com.my.tutipal_back.ai.quota.MutableClock;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.learning.conversation.ConversationTestSupport;
import com.my.tutipal_back.learning.conversation.repository.ConversationStore;
import com.my.tutipal_back.learning.conversation.repository.FakeConversationStore;
import com.my.tutipal_back.learning.conversation.service.ConversationBinding;
import com.my.tutipal_back.learning.conversation.service.ConversationService;
import com.my.tutipal_back.learning.conversation.service.StoredConversationMemory;
import com.my.tutipal_back.learning.tutor.service.ConversationMemory;
import com.my.tutipal_back.sessions.entity.SessionRole;
import com.my.tutipal_back.sessions.service.StudySessionService;

import tools.jackson.databind.JsonNode;

/**
 * PT-106 REST endpoints over HTTP with the real ConversationService on an in-memory store:
 * isolation (AC-6, AC-7), session checks on create (AC-8), delete (AC-9), memories (AC-10), empty states (AC-14),
 * paging (AC-18).
 */
class ConversationControllerWebTest {

    private static final long ME = 2L;
    private static final long OTHER = 3L;
    private static final long SESSION = 10L;

    private final FakeConversationStore store = new FakeConversationStore();
    private final StudySessionService sessions = mock(StudySessionService.class);
    private final MutableClock clock = new MutableClock(NOW);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        login(ME);
        when(sessions.requireActiveRole(ME, SESSION)).thenReturn(SessionRole.STUDENT);
        when(sessions.chatSettings(ME, SESSION))
                .thenReturn(new com.my.tutipal_back.sessions.dto.ChatSettings("파이썬", 2, true, true));
        mvc = ConversationTestSupport.mvc(new ConversationController(new ConversationService(store, sessions, clock)));
    }

    @AfterEach
    void tearDown() {
        logout();
    }

    private static JsonNode body(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static boolean isNullOrMissing(JsonNode node, String field) {
        return node.get(field) == null || node.get(field).isNull();
    }

    private ResultActions postJson(String path, String json, Object... vars) throws Exception {
        return mvc.perform(post(path, vars).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    // ------------------------------------------------------------------ AC-14 empty states

    @Test
    void emptyListIs200WithNoItemsNoCursorAndNoMore() throws Exception {
        JsonNode page = body(mvc.perform(get("/api/conversations")).andExpect(status().isOk()));

        assertThat(page.get("items").isArray()).isTrue();
        assertThat(page.get("items").size()).isZero();
        assertThat(isNullOrMissing(page, "nextCursor")).isTrue();
        assertThat(page.get("hasMore").asBoolean()).isFalse();
    }

    @Test
    void createdEmptyConversationIsListedWithNullTitleAndHasAnEmptyMessagePage() throws Exception {
        JsonNode created = body(postJson("/api/conversations", "{\"topic\":\"수학\"}")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/api/conversations/"
                        + firstConversationId()))));
        String id = created.get("id").asString();
        assertThat(created.get("id").isString()).isTrue();

        JsonNode list = body(mvc.perform(get("/api/conversations")).andExpect(status().isOk()));
        assertThat(list.get("items").size()).isEqualTo(1);
        JsonNode item = list.get("items").get(0);
        assertThat(item.get("id").asString()).isEqualTo(id);
        assertThat(item.get("topic").asString()).isEqualTo("수학");
        assertThat(isNullOrMissing(item, "title")).isTrue();
        assertThat(isNullOrMissing(item, "sessionId")).isTrue();
        assertThat(item.get("messageCount").asInt()).isZero();

        JsonNode messages = body(mvc.perform(get("/api/conversations/{id}/messages", id)).andExpect(status().isOk()));
        assertThat(messages.get("items").size()).isZero();
        assertThat(messages.get("hasMore").asBoolean()).isFalse();
        assertThat(isNullOrMissing(messages, "nextBefore")).isTrue();
    }

    /** The fake hands out ids from 1, so the first created conversation is 1. */
    private static String firstConversationId() {
        return "1";
    }

    // ------------------------------------------------------------------ AC-8 create

    @Test
    void personalCreateWithoutTopicIs400AndCreatesNothing() throws Exception {
        postJson("/api/conversations", "{}").andExpect(status().isBadRequest());
        postJson("/api/conversations", "{\"topic\":\"  \"}").andExpect(status().isBadRequest());

        assertThat(store.calls).doesNotContain("insertConversation");
    }

    @Test
    void personalCreateWithTopicOver30CharactersIs400() throws Exception {
        postJson("/api/conversations", "{\"topic\":\"" + "가".repeat(31) + "\"}").andExpect(status().isBadRequest());

        assertThat(store.calls).doesNotContain("insertConversation");
    }

    @Test
    void studentCreatesSessionConversationWithoutTopic() throws Exception {
        JsonNode created = body(postJson("/api/conversations", "{\"sessionId\":10}").andExpect(status().isCreated()));

        assertThat(created.get("sessionId").asString()).isEqualTo("10");
        ConversationStore.Conversation saved = store.conversation(Long.parseLong(created.get("id").asString()))
                .orElseThrow();
        assertThat(saved.userId()).isEqualTo(ME);
        assertThat(saved.sessionId()).isEqualTo(SESSION);
        assertThat(saved.topic()).isNotBlank().hasSizeLessThanOrEqualTo(30);
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 410})
    void sessionCreateIsRefusedWhenNotParticipantOrEnded(int code) throws Exception {
        when(sessions.requireActiveRole(ME, SESSION)).thenThrow(new ApiException(HttpStatus.valueOf(code), "접근 불가"));

        postJson("/api/conversations", "{\"sessionId\":10}").andExpect(status().is(code));

        assertThat(store.calls).doesNotContain("insertConversation");
    }

    @Test
    void teacherCannotCreateSessionConversation() throws Exception {
        when(sessions.requireActiveRole(ME, SESSION)).thenReturn(SessionRole.TEACHER);

        postJson("/api/conversations", "{\"sessionId\":10}").andExpect(status().isForbidden());

        assertThat(store.calls).doesNotContain("insertConversation");
    }

    @Test
    void endedSessionConversationIsStillViewableAndDeletableByItsOwner() throws Exception {
        ConversationStore.Conversation c = store.seedConversation(ME, SESSION, "파이썬", NOW);
        store.seedMessages(c.id(), 2, NOW);
        when(sessions.requireActiveRole(anyLong(), anyLong()))
                .thenThrow(new ApiException(HttpStatus.GONE, "끝난 수업이에요"));

        mvc.perform(get("/api/conversations/{id}", c.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(String.valueOf(c.id())));
        mvc.perform(get("/api/conversations/{id}/messages", c.id())).andExpect(status().isOk());
        mvc.perform(delete("/api/conversations/{id}", c.id())).andExpect(status().isNoContent());

        // soft delete (PM decision): hidden from the owner at once, the row stays until the purge
        assertThat(store.findOwned(ME, c.id())).isEmpty();
        assertThat(store.deletedAt(c.id())).contains(NOW);
    }

    // ------------------------------------------------------------------ AC-6 another user's conversation

    @Test
    void anotherUsersConversationIs404EverywhereAndNothingChanges() throws Exception {
        ConversationStore.Conversation theirs = store.seedConversation(OTHER, null, "수학", NOW);
        store.seedMessages(theirs.id(), 4, NOW);
        store.seedSummary(theirs.id(), "남의 요약", 1, 2, NOW);
        ConversationStore.Memory memory = store.seedMemory(OTHER, null, theirs.id(), "남의 기억", NOW);
        String before = store.snapshot();

        mvc.perform(get("/api/conversations/{id}", theirs.id())).andExpect(status().isNotFound());
        mvc.perform(get("/api/conversations/{id}/messages", theirs.id())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/conversations/{id}", theirs.id())).andExpect(status().isNotFound());
        postJson("/api/conversations/{id}/memories", "{\"text\":\"훔친 기억\"}", theirs.id())
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/conversations/{id}/memories/{memoryId}", theirs.id(), memory.id()))
                .andExpect(status().isNotFound());

        assertThat(store.snapshot()).isEqualTo(before);
        assertThat(store.calls).noneMatch(call -> call.startsWith("delete:") || call.startsWith("insertMemory")
                || call.startsWith("deleteMemory"));
    }

    @Test
    void missingConversationIs404() throws Exception {
        mvc.perform(get("/api/conversations/{id}", 999)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/conversations/{id}", 999)).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ AC-7 list scope

    @Test
    void listWithoutSessionIdShowsOnlyMyPersonalConversations() throws Exception {
        ConversationStore.Conversation personal = store.seedConversation(ME, null, "수학", NOW);
        store.seedConversation(ME, SESSION, "파이썬", NOW);
        store.seedConversation(ME, 11L, "자바", NOW);
        store.seedConversation(OTHER, null, "수학", NOW);

        JsonNode page = body(mvc.perform(get("/api/conversations")).andExpect(status().isOk()));

        assertThat(ids(page)).containsExactly(String.valueOf(personal.id()));
    }

    @Test
    void listWithSessionIdShowsOnlyMyConversationsInThatSession() throws Exception {
        store.seedConversation(ME, null, "수학", NOW);
        ConversationStore.Conversation mine = store.seedConversation(ME, SESSION, "파이썬", NOW);
        store.seedConversation(ME, 11L, "자바", NOW);
        store.seedConversation(OTHER, SESSION, "파이썬", NOW);

        JsonNode page = body(mvc.perform(get("/api/conversations").param("sessionId", "10"))
                .andExpect(status().isOk()));

        assertThat(ids(page)).containsExactly(String.valueOf(mine.id()));
        assertThat(page.get("items").get(0).get("sessionId").asString()).isEqualTo("10");
    }

    private static List<String> ids(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(item -> out.add(item.get("id").asString()));
        return out;
    }

    // ------------------------------------------------------------------ detail

    @Test
    void detailShowsActiveSummaryAndThisConversationsMemories() throws Exception {
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);
        store.seedMessages(c.id(), 30, NOW);
        store.seedSummary(c.id(), "옛 요약", 1, 5, NOW);
        store.seedSummary(c.id(), "지금 요약", 6, 10, NOW.plusSeconds(60));
        ConversationStore.Memory memory = store.seedMemory(ME, null, c.id(), "내 기억", NOW);
        ConversationStore.Conversation other = store.seedConversation(ME, null, "수학", NOW);
        store.seedMemory(ME, null, other.id(), "다른 대화 기억", NOW);

        JsonNode detail = body(mvc.perform(get("/api/conversations/{id}", c.id())).andExpect(status().isOk()));

        assertThat(detail.get("id").asString()).isEqualTo(String.valueOf(c.id()));
        assertThat(detail.get("topic").asString()).isEqualTo("수학");
        assertThat(detail.get("messageCount").asInt()).isEqualTo(30);
        assertThat(detail.get("summary").get("text").asString()).isEqualTo("지금 요약");
        assertThat(detail.get("summary").get("updatedAt")).isNotNull();
        assertThat(detail.get("memories").size()).isEqualTo(1);
        assertThat(detail.get("memories").get(0).get("id").asString()).isEqualTo(String.valueOf(memory.id()));
        assertThat(detail.get("memories").get(0).get("text").asString()).isEqualTo("내 기억");
    }

    @Test
    void detailWithoutSummaryHasNullSummary() throws Exception {
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);

        JsonNode detail = body(mvc.perform(get("/api/conversations/{id}", c.id())).andExpect(status().isOk()));

        assertThat(isNullOrMissing(detail, "summary")).isTrue();
        assertThat(detail.get("memories").size()).isZero();
    }

    // ------------------------------------------------------------------ AC-9 delete

    /** Soft delete (PM decision): one deleted_at update under the row lock; everything of it is hidden at once. */
    @Test
    void deleteLocksThenSoftDeletesOnceAndHidesEverythingOfIt() throws Exception {
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);
        store.seedMessages(c.id(), 6, NOW);
        store.seedSummary(c.id(), "요약", 1, 2, NOW);
        store.seedMemory(ME, null, c.id(), "기억", NOW);
        ConversationStore.Conversation kept = store.seedConversation(ME, null, "수학", NOW);
        ConversationStore.Memory keptMemory = store.seedMemory(ME, null, kept.id(), "남는 기억", NOW);

        mvc.perform(delete("/api/conversations/{id}", c.id())).andExpect(status().isNoContent());

        List<String> relevant = store.calls.stream()
                .filter(call -> call.equals("lockOwned:" + c.id()) || call.startsWith("delete:")).toList();
        assertThat(relevant).containsExactly("lockOwned:" + c.id(), "delete:" + c.id());
        assertThat(store.deletedAt(c.id())).contains(NOW);
        // invisible through every port read (the rows themselves wait for the purge)
        assertThat(store.findOwned(ME, c.id())).isEmpty();
        assertThat(store.messagesBefore(c.id(), null, 100)).isEmpty();
        assertThat(store.activeSummary(c.id())).isEmpty();
        assertThat(store.memoriesOf(c.id())).isEmpty();
        assertThat(store.memories(ME, null, 20)).extracting(ConversationStore.Memory::id)
                .containsExactly(keptMemory.id());
        assertThat(store.countMemories(ME, null)).isEqualTo(1);
        assertThat(store.messagesOf(c.id())).hasSize(6);
        assertThat(store.summariesOf(c.id())).hasSize(1);
        mvc.perform(get("/api/conversations/{id}", c.id())).andExpect(status().isNotFound());
        mvc.perform(get("/api/conversations/{id}/messages", c.id())).andExpect(status().isNotFound());
    }


    @Test
    void afterDeleteRecallHasNoneOfItsSummaryOrMemories() throws Exception {
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);
        store.seedMessages(c.id(), 6, NOW);
        ConversationStore.Summary summary = store.seedSummary(c.id(), "지운 대화 요약", 1, 2, NOW);
        store.seedMemory(ME, null, c.id(), "지운 대화 기억", NOW);
        ConversationStore.Conversation kept = store.seedConversation(ME, null, "수학", NOW);
        ConversationStore.Memory keptMemory = store.seedMemory(ME, null, kept.id(), "남는 기억", NOW);

        mvc.perform(delete("/api/conversations/{id}", c.id())).andExpect(status().isNoContent());

        ConversationBinding binding = new ConversationBinding();
        StoredConversationMemory memory = new StoredConversationMemory(store, binding);
        ConversationMemory.Recall[] recall = new ConversationMemory.Recall[1];
        binding.run(new ConversationBinding.Bound(ME, AgentDtos.Scope.personal(), c.id(), summary.id(), 0L),
                () -> recall[0] = memory.recall(ME, AgentDtos.Scope.personal(), "질문", List.of()));

        assertThat(recall[0].summary()).isNull();
        assertThat(recall[0].memories()).extracting(ConversationMemory.StoredMemory::id)
                .containsExactly("m_" + keptMemory.id());
    }

    // ------------------------------------------------------------------ AC-10 memories

    @Test
    void savingAMemoryReturns201WithIdTextAndCreatedAt() throws Exception {
        ConversationStore.Conversation c = store.seedConversation(ME, SESSION, "파이썬", NOW);

        JsonNode saved = body(postJson("/api/conversations/{id}/memories", "{\"text\":\"range는 0부터\"}", c.id())
                .andExpect(status().isCreated()));

        assertThat(saved.get("id").isString()).isTrue();
        assertThat(saved.get("text").asString()).isEqualTo("range는 0부터");
        assertThat(saved.get("createdAt")).isNotNull();
        ConversationStore.Memory stored = store.allMemories().getFirst();
        assertThat(stored.userId()).isEqualTo(ME);
        assertThat(stored.sessionId()).isEqualTo(SESSION);
        assertThat(stored.conversationId()).isEqualTo(c.id());
        assertThat(String.valueOf(stored.id())).isEqualTo(saved.get("id").asString());
    }

    @Test
    void memoryOf1000CharactersIsAccepted() throws Exception {
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);

        postJson("/api/conversations/{id}/memories", "{\"text\":\"" + "가".repeat(1000) + "\"}", c.id())
                .andExpect(status().isCreated());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "1001"})
    void blankOrTooLongMemoryIs400(String kind) throws Exception {
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);
        String text = kind.equals("1001") ? "가".repeat(1001) : kind;

        postJson("/api/conversations/{id}/memories", JSON.writeValueAsString(Map.of("text", text)), c.id())
                .andExpect(status().isBadRequest());

        assertThat(store.allMemories()).isEmpty();
    }

    @Test
    void twentyFirstMemoryInTheSameScopeIs409() throws Exception {
        ConversationStore.Conversation c = store.seedConversation(ME, SESSION, "파이썬", NOW);
        for (int i = 0; i < 20; i++) {
            store.seedMemory(ME, SESSION, c.id(), "기억 " + i, NOW);
        }

        postJson("/api/conversations/{id}/memories", "{\"text\":\"하나 더\"}", c.id())
                .andExpect(status().isConflict());

        assertThat(store.allMemories()).hasSize(20);
    }

    @Test
    void memoriesInOtherScopesDoNotCountTowardTheLimit() throws Exception {
        ConversationStore.Conversation sessionConversation = store.seedConversation(ME, SESSION, "파이썬", NOW);
        for (int i = 0; i < 20; i++) {
            store.seedMemory(ME, SESSION, sessionConversation.id(), "수업 기억 " + i, NOW);
        }
        ConversationStore.Conversation personal = store.seedConversation(ME, null, "수학", NOW);

        postJson("/api/conversations/{id}/memories", "{\"text\":\"개인 기억\"}", personal.id())
                .andExpect(status().isCreated());
    }

    @Test
    void deletingAMemoryIs204ThenAbsentIs404AndNextRecallExcludesIt() throws Exception {
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);
        ConversationStore.Memory gone = store.seedMemory(ME, null, c.id(), "지울 기억", NOW);
        ConversationStore.Memory kept = store.seedMemory(ME, null, c.id(), "남길 기억", NOW.plusSeconds(1));

        mvc.perform(delete("/api/conversations/{id}/memories/{memoryId}", c.id(), gone.id()))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/conversations/{id}/memories/{memoryId}", c.id(), gone.id()))
                .andExpect(status().isNotFound());

        ConversationBinding binding = new ConversationBinding();
        StoredConversationMemory memory = new StoredConversationMemory(store, binding);
        ConversationMemory.Recall[] recall = new ConversationMemory.Recall[1];
        binding.run(new ConversationBinding.Bound(ME, AgentDtos.Scope.personal(), c.id(), null, 0L),
                () -> recall[0] = memory.recall(ME, AgentDtos.Scope.personal(), "질문", List.of()));
        assertThat(recall[0].memories()).extracting(ConversationMemory.StoredMemory::id)
                .containsExactly("m_" + kept.id());
    }

    @Test
    void deletingAMemoryOfAnotherConversationThroughMyConversationIs404() throws Exception {
        ConversationStore.Conversation mine = store.seedConversation(ME, null, "수학", NOW);
        ConversationStore.Conversation theirs = store.seedConversation(OTHER, null, "수학", NOW);
        ConversationStore.Memory theirMemory = store.seedMemory(OTHER, null, theirs.id(), "남의 기억", NOW);

        mvc.perform(delete("/api/conversations/{id}/memories/{memoryId}", mine.id(), theirMemory.id()))
                .andExpect(status().isNotFound());

        assertThat(store.allMemories()).hasSize(1);
    }

    // ------------------------------------------------------------------ AC-18 paging

    @Test
    void listIsKeysetPagedNewestFirstWithDefaultLimit20() throws Exception {
        List<ConversationStore.Conversation> all = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            // pairs share a timestamp so the id tie-break matters
            all.add(store.seedConversation(ME, null, "수학", NOW.plus(Duration.ofMinutes(i / 2))));
        }
        List<String> expected = all.stream()
                .sorted(Comparator.comparing(ConversationStore.Conversation::lastMessageAt)
                        .thenComparing(ConversationStore.Conversation::id).reversed())
                .map(c -> String.valueOf(c.id()))
                .toList();

        JsonNode first = body(mvc.perform(get("/api/conversations")).andExpect(status().isOk()));
        assertThat(ids(first)).containsExactlyElementsOf(expected.subList(0, 20));
        assertThat(first.get("hasMore").asBoolean()).isTrue();
        String cursor = first.get("nextCursor").asString();
        assertThat(cursor).isNotBlank();

        JsonNode second = body(mvc.perform(get("/api/conversations").param("cursor", cursor))
                .andExpect(status().isOk()));
        assertThat(ids(second)).containsExactlyElementsOf(expected.subList(20, 25));
        assertThat(second.get("hasMore").asBoolean()).isFalse();
        assertThat(isNullOrMissing(second, "nextCursor")).isTrue();
    }

    @Test
    void listLimitUpTo50IsAcceptedAndOver50Is400() throws Exception {
        mvc.perform(get("/api/conversations").param("limit", "50")).andExpect(status().isOk());
        mvc.perform(get("/api/conversations").param("limit", "51")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/conversations").param("limit", "0")).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"garbage", "!!!", "eyJub3QiOiJjdXJzb3IifQ"})
    void badListCursorIs400(String cursor) throws Exception {
        store.seedConversation(ME, null, "수학", NOW);

        mvc.perform(get("/api/conversations").param("cursor", cursor)).andExpect(status().isBadRequest());
    }

    @Test
    void messagesArePagedNewestPageFirstAscendingInsideEachPage() throws Exception {
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);
        List<ConversationStore.Message> all = store.seedMessages(c.id(), 120, NOW);
        List<String> allIds = all.stream().map(m -> String.valueOf(m.id())).toList();

        JsonNode first = body(mvc.perform(get("/api/conversations/{id}/messages", c.id())).andExpect(status().isOk()));
        assertThat(ids(first)).containsExactlyElementsOf(allIds.subList(70, 120));
        assertThat(first.get("hasMore").asBoolean()).isTrue();
        assertThat(first.get("items").get(0).get("role").asString()).isIn("user", "assistant");
        assertThat(first.get("items").get(49).get("content").asString()).isEqualTo("m120");
        String before = first.get("nextBefore").asString();
        assertThat(before).isEqualTo(allIds.get(70));

        JsonNode second = body(mvc.perform(get("/api/conversations/{id}/messages", c.id()).param("before", before))
                .andExpect(status().isOk()));
        assertThat(ids(second)).containsExactlyElementsOf(allIds.subList(20, 70));
        assertThat(second.get("hasMore").asBoolean()).isTrue();

        JsonNode last = body(mvc.perform(get("/api/conversations/{id}/messages", c.id())
                        .param("before", second.get("nextBefore").asString()))
                .andExpect(status().isOk()));
        assertThat(ids(last)).containsExactlyElementsOf(allIds.subList(0, 20));
        assertThat(last.get("hasMore").asBoolean()).isFalse();
        assertThat(isNullOrMissing(last, "nextBefore")).isTrue();
    }

    @Test
    void messageLimitUpTo100IsAcceptedAndOver100OrBadBeforeIs400() throws Exception {
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);
        store.seedMessages(c.id(), 3, NOW);

        mvc.perform(get("/api/conversations/{id}/messages", c.id()).param("limit", "100")).andExpect(status().isOk());
        mvc.perform(get("/api/conversations/{id}/messages", c.id()).param("limit", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/conversations/{id}/messages", c.id()).param("before", "abc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listItemsCarryTitleCountAndLastMessageAt() throws Exception {
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);
        store.seedMessages(c.id(), 2, Instant.parse("2026-11-25T03:00:00Z"));

        JsonNode item = body(mvc.perform(get("/api/conversations")).andExpect(status().isOk())).get("items").get(0);

        assertThat(item.get("messageCount").asInt()).isEqualTo(2);
        assertThat(item.get("lastMessageAt")).isNotNull();
    }
}
