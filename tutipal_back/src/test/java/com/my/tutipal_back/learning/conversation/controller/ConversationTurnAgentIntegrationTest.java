package com.my.tutipal_back.learning.conversation.controller;

import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.JSON;
import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.NOW;
import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.eventNames;
import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.login;
import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.logout;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.my.tutipal_back.ai.client.AgentListener;
import com.my.tutipal_back.ai.client.AgentOutcome;
import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.ai.client.AiSlots;
import com.my.tutipal_back.ai.dto.AgentDtos;
import com.my.tutipal_back.ai.quota.AiQuota;
import com.my.tutipal_back.ai.quota.AiQuotaFixtures;
import com.my.tutipal_back.ai.quota.MutableClock;
import com.my.tutipal_back.learning.conversation.ConversationTestSupport;
import com.my.tutipal_back.learning.conversation.repository.ConversationStore;
import com.my.tutipal_back.learning.conversation.repository.FakeConversationStore;
import com.my.tutipal_back.learning.conversation.service.ConversationBinding;
import com.my.tutipal_back.learning.conversation.service.ConversationService;
import com.my.tutipal_back.learning.conversation.service.ConversationSummarizer;
import com.my.tutipal_back.learning.conversation.service.ConversationTurnService;
import com.my.tutipal_back.learning.conversation.service.StoredConversationMemory;
import com.my.tutipal_back.learning.quiz.service.QuizVariantAccess;
import com.my.tutipal_back.learning.tutor.controller.TutorController;
import com.my.tutipal_back.learning.tutor.service.AgentContextAssembler;
import com.my.tutipal_back.learning.tutor.service.TutorService;
import com.my.tutipal_back.rag.service.RagSearchService;
import com.my.tutipal_back.sessions.dto.ChatSettings;
import com.my.tutipal_back.sessions.entity.SessionRole;
import com.my.tutipal_back.sessions.service.StudySessionService;

/**
 * PT-106 contract test for the ThreadLocal binding: the REAL TutorService (agentEnabled=true, only AiClient and the
 * other collaborators mocked) with the real AgentContextAssembler, StoredConversationMemory and ConversationBinding.
 * Bound recall (AC-2), unbound /api/tutor/chat (AC-3), legacy path (AC-5), scope isolation (AC-7),
 * long conversation (AC-19). This is the only guard that TutorService assembles on the caller thread.
 */
class ConversationTurnAgentIntegrationTest {

    private static final long ME = 2L;
    private static final long OTHER = 3L;
    private static final long SESSION = 10L;
    private static final Pattern OPAQUE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final AgentDtos.Meta META = new AgentDtos.Meta("student", "concept", List.of("classify", "respond"),
            1, 1, false, 0, null, false, new AgentDtos.ContextReport(100, 3000,
                    Map.of("messages", 0, "memories", 0, "materials", 0, "screenSummary", 0), List.of()),
            List.of(), null);

    private final FakeConversationStore store = new FakeConversationStore();
    private final AiClient ai = mock(AiClient.class);
    private final StudySessionService sessions = mock(StudySessionService.class);
    private final RagSearchService rag = mock(RagSearchService.class);
    private final AiSlots slots = mock(AiSlots.class);
    private final QuizVariantAccess variants = mock(QuizVariantAccess.class);
    private final ConversationSummarizer summarizer = mock(ConversationSummarizer.class);
    private final MutableClock clock = new MutableClock(NOW);
    private final AiQuota quota = AiQuotaFixtures.quota(20, Duration.ofHours(1), clock);
    private final List<AgentDtos.AgentChatRequest> sent = Collections.synchronizedList(new ArrayList<>());
    private final List<List<AiClient.Message>> legacySent = Collections.synchronizedList(new ArrayList<>());
    private ConversationBinding binding;
    private MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        login(ME);
        when(slots.run(any(Supplier.class))).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
        when(slots.run(any(Duration.class), any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        when(sessions.requireActiveRole(ME, SESSION)).thenReturn(SessionRole.STUDENT);
        when(sessions.chatSettings(ME, SESSION)).thenReturn(new ChatSettings("파이썬", 2, true, true));
        when(rag.search(eq(SESSION), anyString(), anyInt())).thenReturn(List.of());
        when(ai.agentStream(any(), any())).thenAnswer(inv -> {
            sent.add(inv.getArgument(0));
            AgentListener listener = inv.getArgument(1);
            listener.onStart(List.of());
            listener.onDelta("답");
            return new AgentOutcome.Completed(META);
        });
        doAnswer(inv -> {
            legacySent.add(List.copyOf(inv.<List<AiClient.Message>>getArgument(1)));
            Consumer<String> onDelta = inv.getArgument(2);
            onDelta.accept("예전 답");
            return null;
        }).when(ai).chatStream(anyString(), anyList(), any());
        build(true);
    }

    private void build(boolean agentEnabled) {
        binding = new ConversationBinding();
        StoredConversationMemory memory = new StoredConversationMemory(store, binding);
        TutorService tutor = new TutorService(ai, sessions, rag, slots, quota, variants,
                new AgentContextAssembler(memory), agentEnabled);
        ConversationService conversations = new ConversationService(store, sessions, clock);
        ConversationTurnService turns = new ConversationTurnService(conversations, tutor, sessions, binding,
                summarizer);
        mvc = ConversationTestSupport.mvc(new ConversationTurnController(turns), new TutorController(tutor));
    }

    @AfterEach
    void tearDown() {
        logout();
    }

    private String stream(String path, String json, Object... vars) throws Exception {
        MvcResult result = mvc.perform(post(path, vars).accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(5_000);
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String turn(long conversationId, String question) throws Exception {
        return stream("/api/conversations/{id}/messages", JSON.writeValueAsString(Map.of("content", question)),
                conversationId);
    }

    private static List<String> contents(List<AgentDtos.ContextMessage> messages) {
        return messages.stream().map(AgentDtos.ContextMessage::content).toList();
    }

    private static List<String> contentsOf(List<ConversationStore.Message> messages) {
        return messages.stream().map(ConversationStore.Message::content).toList();
    }

    /** Session-10 conversation with 50 messages, an active summary over 1..10 and memories in many scopes. */
    private record Seeded(ConversationStore.Conversation conversation, List<ConversationStore.Message> messages,
                          ConversationStore.Summary summary, List<ConversationStore.Memory> sessionMemories) {
    }

    private Seeded seedSession() {
        ConversationStore.Conversation c = store.seedConversation(ME, SESSION, "파이썬", NOW);
        List<ConversationStore.Message> messages = store.seedMessages(c.id(), 50, NOW);
        ConversationStore.Summary summary = store.seedSummary(c.id(), "SUMMARY-S", messages.get(0).id(),
                messages.get(9).id(), NOW);
        List<ConversationStore.Memory> sessionMemories = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            sessionMemories.add(store.seedMemory(ME, SESSION, c.id(), "SESSION10-MEMORY-" + i, NOW.plusSeconds(i)));
        }
        ConversationStore.Conversation session11 = store.seedConversation(ME, 11L, "자바", NOW);
        store.seedMemory(ME, 11L, session11.id(), "SESSION11-MEMORY", NOW.plusSeconds(100));
        ConversationStore.Conversation personal = store.seedConversation(ME, null, "수학", NOW);
        store.seedMemory(ME, null, personal.id(), "PERSONAL-MEMORY", NOW.plusSeconds(100));
        ConversationStore.Conversation theirs = store.seedConversation(OTHER, SESSION, "파이썬", NOW);
        store.seedMemory(OTHER, SESSION, theirs.id(), "OTHER-USER-MEMORY", NOW.plusSeconds(100));
        return new Seeded(c, messages, summary, sessionMemories);
    }

    // ------------------------------------------------------------------ AC-2 bound recall

    @Test
    void boundTurnSendsServerHistoryActiveSummaryAndNewest20MemoriesOfTheSameScope() throws Exception {
        Seeded s = seedSession();

        String body = turn(s.conversation().id(), "새 질문");

        assertThat(eventNames(body)).contains("agent").endsWith("saved", "done");
        assertThat(sent).hasSize(1);
        AgentDtos.AgentChatRequest request = sent.getFirst();
        assertThat(request.question()).isEqualTo("새 질문");
        AgentDtos.Context context = request.context();
        assertThat(context.scope()).isEqualTo(AgentDtos.Scope.session(SESSION));
        // after the summary (messages 11..50 = 40), the newest 39 = 12..50
        assertThat(contents(context.messages())).containsExactlyElementsOf(contentsOf(s.messages().subList(11, 50)));
        assertThat(context.summary()).isEqualTo(new AgentDtos.Summary("s_" + s.summary().id(),
                AgentDtos.Scope.session(SESSION), "SUMMARY-S"));
        List<String> newest20 = new ArrayList<>();
        for (int i = 24; i >= 5; i--) {
            newest20.add("m_" + s.sessionMemories().get(i).id());
        }
        assertThat(context.memories()).extracting(AgentDtos.Memory::id).containsExactlyElementsOf(newest20);
        assertThat(context.memories()).extracting(AgentDtos.Memory::text)
                .noneMatch(t -> t.contains("SESSION11") || t.contains("PERSONAL") || t.contains("OTHER-USER"));
        assertThat(context.summary().id()).matches(OPAQUE_ID);
        assertThat(context.memories()).extracting(AgentDtos.Memory::id).allMatch(id -> OPAQUE_ID.matcher(id).matches());
    }

    @Test
    void memoryQueryUsesTheAuthenticatedUserAndTheConversationSession() throws Exception {
        Seeded s = seedSession();

        turn(s.conversation().id(), "질문");

        assertThat(store.memoryQueries).isNotEmpty();
        assertThat(store.memoryQueries).allSatisfy(args -> {
            assertThat(args[0]).isEqualTo(ME);
            assertThat(args[1]).isEqualTo(SESSION);
            assertThat((Integer) args[2]).isLessThanOrEqualTo(20);
        });
    }

    /** Runs the change once, on the turn's thread, between binding and assemble (chatSettings is called there). */
    private void duringTurn(Runnable change) {
        AtomicBoolean done = new AtomicBoolean(false);
        when(sessions.chatSettings(ME, SESSION)).thenAnswer(inv -> {
            if (binding.current().isPresent() && done.compareAndSet(false, true)) {
                change.run();
            }
            return new ChatSettings("파이썬", 2, true, true);
        });
    }

    @Test
    void boundSummaryIsStillUsedWhenSupersededDuringTheTurn() throws Exception {
        Seeded s = seedSession();
        duringTurn(() -> store.seedSummary(s.conversation().id(), "NEWER-SUMMARY", s.messages().get(10).id(),
                s.messages().get(20).id(), NOW));

        turn(s.conversation().id(), "질문");

        assertThat(sent.getFirst().context().summary().id()).isEqualTo("s_" + s.summary().id());
        assertThat(sent.getFirst().context().summary().text()).isEqualTo("SUMMARY-S");
    }

    @Test
    void boundSummaryInvalidatedDuringTheTurnIsNotSent() throws Exception {
        Seeded s = seedSession();
        duringTurn(() -> store.invalidate(s.summary().id(), NOW));

        turn(s.conversation().id(), "질문");

        assertThat(sent.getFirst().context().summary()).isNull();
    }

    @Test
    void boundSummaryDeletedDuringTheTurnIsNotSent() throws Exception {
        Seeded s = seedSession();
        duringTurn(() -> store.removeSummary(s.summary().id()));

        turn(s.conversation().id(), "질문");

        assertThat(sent.getFirst().context().summary()).isNull();
    }

    @Test
    void bindingIsReleasedAfterTheTurn() throws Exception {
        Seeded s = seedSession();

        turn(s.conversation().id(), "질문");

        assertThat(binding.current()).isEmpty();
    }

    // ------------------------------------------------------------------ AC-7 scope

    @Test
    void personalConversationGetsPersonalScopeAndNoSessionMemories() throws Exception {
        seedSession();
        ConversationStore.Conversation personal = store.seedConversation(ME, null, "수학", NOW);
        store.seedMessages(personal.id(), 2, NOW);
        ConversationStore.Memory mine = store.seedMemory(ME, null, personal.id(), "MY-PERSONAL", NOW.plusSeconds(500));

        turn(personal.id(), "개인 질문");

        AgentDtos.Context context = sent.getFirst().context();
        assertThat(sent.getFirst().agent()).isEqualTo("personal");
        assertThat(context.scope()).isEqualTo(AgentDtos.Scope.personal());
        assertThat(context.summary()).isNull();
        assertThat(context.memories()).extracting(AgentDtos.Memory::text)
                .allMatch(t -> t.startsWith("MY-PERSONAL") || t.startsWith("PERSONAL-MEMORY"));
        assertThat(context.memories()).extracting(AgentDtos.Memory::id).contains("m_" + mine.id());
        assertThat(contents(context.messages())).containsExactly("m1", "m2");
    }

    // ------------------------------------------------------------------ AC-19 long conversation

    @Test
    void longConversationSendsAtMost39HistoryMessagesWithTheSummary() throws Exception {
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);
        List<ConversationStore.Message> messages = store.seedMessages(c.id(), 120, NOW);
        ConversationStore.Summary summary = store.seedSummary(c.id(), "LONG-SUMMARY", messages.get(0).id(),
                messages.get(59).id(), NOW);

        turn(c.id(), "질문");

        AgentDtos.Context context = sent.getFirst().context();
        assertThat(context.messages()).hasSize(39);
        assertThat(contents(context.messages())).containsExactlyElementsOf(contentsOf(messages.subList(81, 120)));
        assertThat(context.summary().id()).isEqualTo("s_" + summary.id());
    }

    // ------------------------------------------------------------------ AC-3 unbound /api/tutor/chat

    @Test
    void unboundTutorChatUsesScreenHistoryOnlyEvenWithStoredData() throws Exception {
        ConversationStore.Conversation personal = store.seedConversation(ME, null, "수학", NOW);
        store.seedMessages(personal.id(), 4, NOW);
        store.seedSummary(personal.id(), "STORED-SUMMARY", 1, 2, NOW);
        store.seedMemory(ME, null, personal.id(), "STORED-MEMORY", NOW);

        stream("/api/tutor/chat", """
                {"topic":"수학","messages":[{"role":"user","content":"화면 질문"},
                {"role":"assistant","content":"화면 답"},{"role":"user","content":"지금 질문"}]}
                """);

        AgentDtos.Context context = sent.getFirst().context();
        assertThat(contents(context.messages())).containsExactly("화면 질문", "화면 답");
        assertThat(context.summary()).isNull();
        assertThat(context.memories()).isEmpty();
        assertThat(store.memoryQueries).isEmpty();
    }

    @Test
    void tutorChatAfterAConversationTurnIsUnboundAgain() throws Exception {
        Seeded s = seedSession();
        turn(s.conversation().id(), "대화 질문");

        stream("/api/tutor/chat", """
                {"topic":"수학","sessionId":10,"messages":[{"role":"user","content":"화면 질문"}]}
                """);

        AgentDtos.Context context = sent.get(1).context();
        assertThat(context.messages()).isEmpty();
        assertThat(context.summary()).isNull();
        assertThat(context.memories()).isEmpty();
    }

    // ------------------------------------------------------------------ AC-5 legacy path

    @Test
    void agentDisabledStillSendsStoredHistoryPlusQuestion() throws Exception {
        build(false);
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW);
        List<ConversationStore.Message> messages = store.seedMessages(c.id(), 6, NOW);
        store.seedSummary(c.id(), "요약", messages.get(0).id(), messages.get(1).id(), NOW);

        String body = turn(c.id(), "예전 경로 질문");

        assertThat(sent).isEmpty();
        assertThat(legacySent).hasSize(1);
        assertThat(legacySent.getFirst()).extracting(AiClient.Message::content)
                .containsExactly("m3", "m4", "m5", "m6", "예전 경로 질문");
        assertThat(legacySent.getFirst()).extracting(AiClient.Message::role)
                .containsExactly("user", "assistant", "user", "assistant", "user");
        assertThat(eventNames(body)).endsWith("saved", "done");
        assertThat(store.messagesOf(c.id()).getLast().content()).isEqualTo("예전 답");
    }
}
