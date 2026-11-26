package com.my.tutipal_back.learning.tutor.service;

import static com.my.tutipal_back.ai.quota.AiQuotaFixtures.hasRoom;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.stubbing.Answer;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;

import com.my.tutipal_back.ai.client.AgentListener;
import com.my.tutipal_back.ai.client.AgentOutcome;
import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.ai.client.AiSlots;
import com.my.tutipal_back.ai.client.AiUnavailableException;
import com.my.tutipal_back.ai.dto.AgentDtos;
import com.my.tutipal_back.ai.quota.AiFeature;
import com.my.tutipal_back.ai.quota.AiQuota;
import com.my.tutipal_back.ai.quota.AiQuotaFixtures;
import com.my.tutipal_back.ai.quota.MutableClock;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.learning.quiz.dto.VariantOriginal;
import com.my.tutipal_back.learning.quiz.entity.Quiz;
import com.my.tutipal_back.learning.quiz.entity.QuizType;
import com.my.tutipal_back.learning.quiz.service.QuizVariantAccess;
import com.my.tutipal_back.learning.tutor.dto.ChatRequest;
import com.my.tutipal_back.rag.dto.RagHit;
import com.my.tutipal_back.rag.service.RagSearchService;
import com.my.tutipal_back.sessions.dto.ChatSettings;
import com.my.tutipal_back.sessions.entity.SessionRole;
import com.my.tutipal_back.sessions.service.StudySessionService;

/**
 * PT-85 Spring agent path inside TutorService.chat: branch (AC-7), sources (AC-8), re-search loop (AC-15),
 * variant rendering and settlement (AC-18/19), help suggestion (AC-4), memory assembly (AC-23), log (AC-26).
 */
@ExtendWith(OutputCaptureExtension.class)
class TutorServiceAgentTest {

    private static final long USER = 2L;
    private static final long SESSION = 10L;
    private static final long QUIZ = 77L;
    private static final String QUESTION = "SECRET-QUESTION-5521 정답 알려줘";
    private static final List<RagHit> HITS = List.of(
            new RagHit("a.pdf", 1, "첫 자료", 0.9), new RagHit("b.pdf", 2, "둘째 자료", 0.8),
            new RagHit("c.pdf", null, "셋째 자료", 0.7));

    private final AiClient ai = mock(AiClient.class);
    private final StudySessionService sessions = mock(StudySessionService.class);
    private final RagSearchService rag = mock(RagSearchService.class);
    private final AiSlots slots = mock(AiSlots.class);
    private final QuizVariantAccess variants = mock(QuizVariantAccess.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-25T00:00:00Z"));
    private AiQuota quota = AiQuotaFixtures.quota(1, Duration.ofHours(1), clock);
    private ConversationMemory memory = (userId, scope, question, history) -> new ConversationMemory.Recall(history, null, List.of());
    private TutorService service;

    private final List<String> events = new ArrayList<>();
    private final List<String> deltas = new ArrayList<>();
    private final List<List<String>> sources = new ArrayList<>();
    private final List<AgentDtos.AgentEvent> agentEvents = new ArrayList<>();
    private final List<AgentDtos.AgentChatRequest> sent = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void collaborators() {
        when(slots.run(any(Supplier.class))).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
        when(slots.run(any(Duration.class), any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        when(sessions.requireActiveRole(USER, SESSION)).thenReturn(SessionRole.STUDENT);
        when(sessions.chatSettings(USER, SESSION)).thenReturn(new ChatSettings("파이썬", 2, true, true));
        when(rag.search(eq(SESSION), anyString(), anyInt())).thenReturn(HITS);
        rebuild(true);
    }

    private void rebuild(boolean agentEnabled) {
        service = new TutorService(ai, sessions, rag, slots, quota, variants, new AgentContextAssembler(memory),
                agentEnabled, StudentQuestionSink.NONE);
    }

    private static ChatRequest personal(String question) {
        return new ChatRequest("수학", List.of(new ChatRequest.Message("user", question)), null, null, null);
    }

    private static ChatRequest student(String question) {
        return new ChatRequest("수학", List.of(new ChatRequest.Message("user", "range가 뭐예요?"),
                new ChatRequest.Message("assistant", "0부터 세요"),
                new ChatRequest.Message("user", question)), SESSION, null, null);
    }

    private static ChatRequest variantRequest() {
        return new ChatRequest("수학", List.of(new ChatRequest.Message("user", "비슷한 문제 내줘")), SESSION, QUIZ, null);
    }

    static AgentDtos.Meta meta(String route, boolean help, String variant, boolean retryable) {
        return new AgentDtos.Meta("student", route, List.of("classify", "compose", "respond"), 1, 1, help, 0, variant,
                retryable, new AgentDtos.ContextReport(500, 3000,
                        Map.of("messages", 0, "memories", 0, "materials", 0, "screenSummary", 0), List.of()),
                List.of(), null);
    }

    private void run(ChatRequest request) {
        OpenedChat ticket = service.openChat(USER, request);
        service.chat(USER, request, ticket, s -> {
            events.add("sources");
            sources.add(s);
        }, d -> {
            events.add("delta");
            deltas.add(d);
        }, e -> {
            events.add("agent");
            agentEvents.add(e);
        });
    }

    /** One FastAPI call: start(all materials) -> deltas -> meta. */
    private Answer<AgentOutcome> completes(AgentDtos.Meta meta, String... pieces) {
        return inv -> {
            AgentDtos.AgentChatRequest request = inv.getArgument(0);
            AgentListener listener = inv.getArgument(1);
            sent.add(request);
            listener.onPing();
            listener.onStart(request.materials().stream().map(AgentDtos.Material::id).toList());
            for (String piece : pieces) {
                listener.onDelta(piece);
            }
            return new AgentOutcome.Completed(meta);
        };
    }

    private Answer<AgentOutcome> needs(String query, Map<String, Object> state) {
        return inv -> {
            sent.add(inv.getArgument(0));
            ((AgentListener) inv.getArgument(1)).onPing();
            return new AgentOutcome.NeedSearch(query, state);
        };
    }

    private Answer<AgentOutcome> variantThen(AgentDtos.VariantResult result, AgentDtos.Meta meta, String... pieces) {
        return inv -> {
            sent.add(inv.getArgument(0));
            AgentListener listener = inv.getArgument(1);
            listener.onVariant(result);
            if (pieces.length > 0) {
                listener.onStart(List.of());
                for (String piece : pieces) {
                    listener.onDelta(piece);
                }
            }
            return new AgentOutcome.Completed(meta);
        };
    }

    private static int invocations(Object mock) {
        return mockingDetails(mock).getInvocations().size();
    }

    // ------------------------------------------------------------------ AC-7 branch

    @Test
    void personalChatRunsPersonalAgentWithAnswerToolOnly() {
        when(ai.agentStream(any(), any())).thenAnswer(completes(meta("concept", false, null, false), "미분은 ", "변화율"));

        run(personal("미분이 뭐야?"));

        assertThat(sent).hasSize(1);
        AgentDtos.AgentChatRequest body = sent.getFirst();
        assertThat(body.agent()).isEqualTo("personal");
        assertThat(body.question()).isEqualTo("미분이 뭐야?");
        assertThat(body.allowedTools()).containsExactly("answer_question");
        assertThat(body.policy().hideAnswers()).isFalse();
        assertThat(body.policy().preferMaterials()).isFalse();
        assertThat(body.materials()).isEmpty();
        assertThat(body.variantSource()).isNull();
        assertThat(body.context().scope()).isEqualTo(AgentDtos.Scope.personal());
        assertThat(deltas).containsExactly("미분은 ", "변화율");
        assertThat(agentEvents).singleElement().satisfies(e -> assertThat(e.answerHidden()).isFalse());
        verify(rag, never()).search(anyLong(), anyString(), anyInt());
        verify(ai, never()).chatStream(anyString(), anyList(), any());
    }

    @Test
    void personalScreenSummaryIsPassedInContext() {
        when(ai.agentStream(any(), any())).thenAnswer(completes(meta("concept", false, null, false), "답"));
        ChatRequest request = new ChatRequest("자바", List.of(new ChatRequest.Message("user", "이 에러 뭐예요?")), null,
                null, "IDE 화면. NullPointerException");

        run(request);

        assertThat(sent.getFirst().context().screenSummary()).isEqualTo("IDE 화면. NullPointerException");
    }

    @Test
    void studentSessionRunsStudentAgentWithTeacherSettingsAndMaterials() {
        when(ai.agentStream(any(), any())).thenAnswer(completes(meta("answer_request", false, null, false), "힌트"));

        run(student(QUESTION));

        AgentDtos.AgentChatRequest body = sent.getFirst();
        assertThat(body.agent()).isEqualTo("student");
        assertThat(body.question()).isEqualTo(QUESTION);
        assertThat(body.policy()).isEqualTo(new AgentDtos.Policy("파이썬", 2, true, true));
        assertThat(body.allowedTools()).containsExactly("answer_question", "material_search");
        assertThat(body.context().scope()).isEqualTo(AgentDtos.Scope.session(SESSION));
        assertThat(body.context().messages()).containsExactly(
                new AgentDtos.ContextMessage("user", "range가 뭐예요?"),
                new AgentDtos.ContextMessage("assistant", "0부터 세요"));
        assertThat(body.materials()).extracting(AgentDtos.Material::content)
                .containsExactly("첫 자료", "둘째 자료", "셋째 자료");
        verify(rag).search(SESSION, QUESTION, 3);
        verify(ai, never()).chatStream(anyString(), anyList(), any());
        assertThat(agentEvents).singleElement().satisfies(e -> {
            assertThat(e.answerHidden()).isTrue();
            assertThat(e.route()).isEqualTo("answer_request");
        });
    }

    @Test
    void teacherSessionUsesExistingChatPathWithoutAgentEvent() {
        when(sessions.requireActiveRole(USER, SESSION)).thenReturn(SessionRole.TEACHER);
        doAnswer(inv -> {
            Consumer<String> onDelta = inv.getArgument(2);
            onDelta.accept("미리보기");
            return null;
        }).when(ai).chatStream(anyString(), anyList(), any());

        run(student("설명해 주세요"));

        assertThat(deltas).containsExactly("미리보기");
        assertThat(agentEvents).isEmpty();
        verify(ai, never()).agentStream(any(), any());
    }

    @Test
    void disabledFlagUsesExistingChatPathForEveryRole() {
        quota = AiQuotaFixtures.quota(5, Duration.ofHours(1), clock);
        rebuild(false);
        doAnswer(inv -> {
            Consumer<String> onDelta = inv.getArgument(2);
            onDelta.accept("기존");
            return null;
        }).when(ai).chatStream(anyString(), anyList(), any());

        run(personal("미분이 뭐야?"));
        run(student("설명해 주세요"));

        assertThat(deltas).containsExactly("기존", "기존");
        assertThat(agentEvents).isEmpty();
        verify(ai, never()).agentStream(any(), any());
    }

    @Test
    void fiveArgumentChatDelegatesWithoutAgentEvents() {
        when(ai.agentStream(any(), any())).thenAnswer(completes(meta("concept", false, null, false), "답"));
        ChatRequest request = personal("미분이 뭐야?");

        service.chat(USER, request, service.openChat(USER, request), s -> { }, deltas::add);

        assertThat(deltas).containsExactly("답");
    }

    @Test
    void lastMessageNotFromUserIsRejectedBeforeReserving() {
        ChatRequest request = new ChatRequest("수학", List.of(new ChatRequest.Message("user", "질문"),
                new ChatRequest.Message("assistant", "답")), null, null, null);

        assertThatThrownBy(() -> service.openChat(USER, request))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(hasRoom(quota, USER, AiFeature.CHAT)).isTrue();
    }

    // ------------------------------------------------------------------ AC-8 events, AC-5 one CHAT

    @Test
    void sourcesComeOnlyFromStartMaterialIdsThenDeltasThenOneAgentEvent() {
        when(ai.agentStream(any(), any())).thenAnswer(inv -> {
            AgentDtos.AgentChatRequest request = inv.getArgument(0);
            AgentListener listener = inv.getArgument(1);
            listener.onStart(List.of(request.materials().get(0).id(), request.materials().get(2).id()));
            listener.onDelta("a");
            listener.onDelta("b");
            return new AgentOutcome.Completed(meta("concept", false, null, false));
        });

        run(student("range 함수가 뭐예요?"));

        assertThat(events).containsExactly("sources", "delta", "delta", "agent");
        assertThat(sources).containsExactly(List.of("a.pdf p.1", "c.pdf"));
    }

    @Test
    void emptyStartSendsNoSources() {
        when(ai.agentStream(any(), any())).thenAnswer(inv -> {
            ((AgentListener) inv.getArgument(1)).onStart(List.of());
            ((AgentListener) inv.getArgument(1)).onDelta("a");
            return new AgentOutcome.Completed(meta("concept", false, null, false));
        });

        run(student("range 함수가 뭐예요?"));

        assertThat(events).containsExactly("delta", "agent");
    }

    @Test
    void oneAgentTurnCountsExactlyOneChat() {
        when(ai.agentStream(any(), any())).thenAnswer(completes(meta("concept", false, null, false), "답"));

        run(student("range 함수가 뭐예요?"));

        assertThat(hasRoom(quota, USER, AiFeature.CHAT)).isFalse();
    }

    @Test
    void agentUnavailableBeforeAnyLineIsRefunded() {
        when(ai.agentStream(any(), any())).thenThrow(new AiUnavailableException());

        assertThatThrownBy(() -> run(student("range 함수가 뭐예요?"))).isInstanceOf(AiUnavailableException.class);

        assertThat(hasRoom(quota, USER, AiFeature.CHAT)).isTrue();
    }

    // ------------------------------------------------------------------ AC-15 re-search loop

    @Test
    void twoResearchRoundsReplaceMaterialsRecheckAccessAndStillCountOneChat() {
        quota = AiQuotaFixtures.quota(2, Duration.ofHours(1), clock);
        rebuild(true);
        when(rag.search(SESSION, "q1", 3)).thenReturn(List.of(new RagHit("q1.pdf", 1, "q1 자료", 0.9)));
        when(rag.search(SESSION, "q2", 3)).thenReturn(List.of(new RagHit("q2a.pdf", 1, "q2 자료 A", 0.9),
                new RagHit("q2b.pdf", 2, "q2 자료 B", 0.8)));
        Map<String, Object> state1 = Map.of("v", 1, "step", "a");
        Map<String, Object> state2 = Map.of("v", 1, "step", "b");
        when(ai.agentStream(any(), any())).thenAnswer(needs("q1", state1)).thenAnswer(needs("q2", state2))
                .thenAnswer(completes(meta("concept", false, null, false), "답"));

        run(student(QUESTION));

        assertThat(sent).hasSize(3);
        assertThat(sent.get(0).search()).isEqualTo(new AgentDtos.Search(0, null));
        assertThat(sent.get(1).search()).isEqualTo(new AgentDtos.Search(1, state1));
        assertThat(sent.get(2).search()).isEqualTo(new AgentDtos.Search(2, state2));
        assertThat(sent.get(1).materials()).extracting(AgentDtos.Material::content).containsExactly("q1 자료");
        assertThat(sent.get(2).materials()).extracting(AgentDtos.Material::content).containsExactly("q2 자료 A", "q2 자료 B");
        assertThat(sources).containsExactly(List.of("q2a.pdf p.1", "q2b.pdf p.2"));
        assertThat(deltas).startsWith("답");

        InOrder order = inOrder(sessions, rag, ai);
        order.verify(rag).search(SESSION, QUESTION, 3);
        order.verify(ai).agentStream(any(), any());
        order.verify(sessions).requireActiveRole(USER, SESSION);
        order.verify(rag).search(SESSION, "q1", 3);
        order.verify(ai).agentStream(any(), any());
        order.verify(sessions).requireActiveRole(USER, SESSION);
        order.verify(rag).search(SESSION, "q2", 3);
        order.verify(ai).agentStream(any(), any());

        assertThat(invocations(slots)).isEqualTo(1);
        quota.reserve(USER, AiFeature.CHAT);
        assertThat(hasRoom(quota, USER, AiFeature.CHAT)).isFalse();
    }

    @Test
    void thirdNeedSearchIsAnErrorWithoutSearching() {
        when(ai.agentStream(any(), any())).thenAnswer(needs("q1", Map.of("v", 1)))
                .thenAnswer(needs("q2", Map.of("v", 1))).thenAnswer(needs("q3", Map.of("v", 1)));

        assertThatThrownBy(() -> run(student(QUESTION))).isInstanceOf(RuntimeException.class);

        verify(ai, times(3)).agentStream(any(), any());
        verify(rag, never()).search(SESSION, "q3", 3);
        assertThat(deltas).isEmpty();
        assertThat(agentEvents).isEmpty();
    }

    @Test
    void needSearchAfterDeltaIsAnErrorWithoutSearching() {
        when(ai.agentStream(any(), any())).thenAnswer(inv -> {
            AgentListener listener = inv.getArgument(1);
            listener.onStart(List.of());
            listener.onDelta("부분");
            return new AgentOutcome.NeedSearch("q1", Map.of("v", 1));
        });

        assertThatThrownBy(() -> run(student(QUESTION))).isInstanceOf(RuntimeException.class);

        verify(ai, times(1)).agentStream(any(), any());
        verify(rag, never()).search(SESSION, "q1", 3);
    }

    @Test
    void oversizedStateIsAnErrorWithoutSearching() {
        when(ai.agentStream(any(), any())).thenAnswer(needs("q1", Map.of("pad", "x".repeat(70_000))));

        assertThatThrownBy(() -> run(student(QUESTION))).isInstanceOf(RuntimeException.class);

        verify(ai, times(1)).agentStream(any(), any());
        verify(rag, never()).search(SESSION, "q1", 3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankQueryIsAnErrorWithoutSearching(String query) {
        when(ai.agentStream(any(), any())).thenAnswer(needs(query, Map.of("v", 1)));

        assertThatThrownBy(() -> run(student(QUESTION))).isInstanceOf(RuntimeException.class);

        verify(rag, times(1)).search(anyLong(), anyString(), anyInt());
        verify(ai, times(1)).agentStream(any(), any());
    }

    @Test
    void queryOver500CharactersIsAnErrorWithoutSearching() {
        when(ai.agentStream(any(), any())).thenAnswer(needs("가".repeat(501), Map.of("v", 1)));

        assertThatThrownBy(() -> run(student(QUESTION))).isInstanceOf(RuntimeException.class);

        verify(rag, times(1)).search(anyLong(), anyString(), anyInt());
    }

    @Test
    void queryOfExactly500CharactersIsSearched() {
        String query = "가".repeat(500);
        when(ai.agentStream(any(), any())).thenAnswer(needs(query, Map.of("v", 1)))
                .thenAnswer(completes(meta("concept", false, null, false), "답"));

        run(student(QUESTION));

        verify(rag).search(SESSION, query, 3);
    }

    @Test
    void lostAccessBeforeResearchIsAnErrorWithoutSearching() {
        when(sessions.requireActiveRole(USER, SESSION)).thenReturn(SessionRole.STUDENT)
                .thenThrow(new ApiException(HttpStatus.GONE, "끝난 수업"));
        when(ai.agentStream(any(), any())).thenAnswer(needs("q1", Map.of("v", 1)));

        assertThatThrownBy(() -> run(student(QUESTION))).isInstanceOf(RuntimeException.class);

        verify(rag, never()).search(SESSION, "q1", 3);
        verify(ai, times(1)).agentStream(any(), any());
    }

    // ------------------------------------------------------------------ AC-4 help suggestion

    @Test
    void helpSuggestionIsAppendedAsDeltaAndFlaggedInAgentEvent() {
        when(ai.agentStream(any(), any())).thenAnswer(completes(meta("answer_request", true, null, false), "힌트"));

        run(student("그래도 모르겠어요"));

        assertThat(deltas).first().isEqualTo("힌트");
        assertThat(deltas).last().isEqualTo(AgentRequests.HELP_SUGGESTION);
        assertThat(events).endsWith("delta", "agent");
        assertThat(agentEvents).singleElement().satisfies(e -> assertThat(e.suggestHelp()).isTrue());
    }

    @Test
    void noHelpSuggestionTextWhenMetaSaysNo() {
        when(ai.agentStream(any(), any())).thenAnswer(completes(meta("answer_request", false, null, false), "힌트"));

        run(student("정답 알려줘"));

        assertThat(String.join("", deltas)).doesNotContain(AgentRequests.HELP_SUGGESTION);
        assertThat(agentEvents.getFirst().suggestHelp()).isFalse();
        assertThat(agentEvents.getFirst().helpDraft()).isNull();
    }

    // ------------------------------------------------------------------ PT-113 help draft

    @Test
    void helpSuggestionTurnCarriesHelpDraftInAgentEvent() {
        when(ai.agentStream(any(), any()))
                .thenAnswer(completes(meta("answer_request", true, null, false), "ANSWER-TEXT-9"));

        run(student(QUESTION));

        assertThat(deltas).last().isEqualTo(AgentRequests.HELP_SUGGESTION);
        assertThat(agentEvents).singleElement().satisfies(e -> assertThat(e.helpDraft())
                .isEqualTo(AgentRequests.helpDraft("파이썬", "answer_request", 1))
                .contains("수업 주제: 파이썬", "질문 종류: 문제 풀이 요청")
                .doesNotContain("SECRET-QUESTION-5521", "ANSWER-TEXT-9"));
    }

    @Test
    void personalTurnNeverHasHelpDraftEvenIfMetaSuggestsHelp() {
        when(ai.agentStream(any(), any())).thenAnswer(completes(meta("answer_request", true, null, false), "답"));

        run(personal("모르겠어요"));

        assertThat(String.join("", deltas)).doesNotContain(AgentRequests.HELP_SUGGESTION);
        assertThat(agentEvents).singleElement().satisfies(e -> {
            assertThat(e.suggestHelp()).isFalse();
            assertThat(e.helpDraft()).isNull();
        });
    }

    // ------------------------------------------------------------------ AC-12 / AC-18 / AC-19 variant

    private static final Quiz QUIZ_ENTITY = new Quiz(SESSION, QuizType.CHOICE, "2 + 3 은?", List.of("4", "5", "6"), 1,
            null, Instant.parse("2026-11-24T00:00:00Z"));
    private static final AgentDtos.VariantQuestion VARIANT = new AgentDtos.VariantQuestion("CHOICE",
            "VARIANT-QUESTION 4 + 4 는?", List.of("7", "8", "9"), 1, null, "EXPLANATION-SECRET 4 더하기 4");

    private void allowVariant() {
        when(variants.requireVariantOriginal(USER, SESSION, QUIZ)).thenReturn(VariantOriginal.of(QUIZ_ENTITY));
    }

    @Test
    void variantSuccessRendersQuestionWithoutAnswersWhenHidden() {
        allowVariant();
        when(ai.agentStream(any(), any())).thenAnswer(variantThen(
                new AgentDtos.VariantResult("ok", 1, VARIANT, null), meta("variant", false, "ok", false)));

        run(variantRequest());

        AgentDtos.AgentChatRequest body = sent.getFirst();
        assertThat(body.allowedTools()).contains("question_variant");
        assertThat(body.variantSource()).isEqualTo(AgentRequests.variantSource(VariantOriginal.of(QUIZ_ENTITY)));
        String text = String.join("", deltas);
        assertThat(text).contains("VARIANT-QUESTION 4 + 4 는?").doesNotContain("EXPLANATION-SECRET");
        assertThat(agentEvents).singleElement().satisfies(e -> {
            assertThat(e.route()).isEqualTo("variant");
            assertThat(e.answerHidden()).isTrue();
            assertThat(e.variant().question()).isEqualTo(VARIANT.question());
            assertThat(e.variant().answerIndex()).isNull();
            assertThat(e.variant().explanation()).isNull();
            assertThat(e.variant().modelAnswer()).isNull();
        });
        verify(variants, times(2)).requireVariantOriginal(USER, SESSION, QUIZ);
    }

    @Test
    void variantSuccessShowsAnswerAndExplanationWhenPolicyAllows() {
        when(sessions.chatSettings(USER, SESSION)).thenReturn(new ChatSettings("파이썬", 2, false, true));
        allowVariant();
        when(ai.agentStream(any(), any())).thenAnswer(variantThen(
                new AgentDtos.VariantResult("ok", 1, VARIANT, null), meta("variant", false, "ok", false)));

        run(variantRequest());

        assertThat(String.join("", deltas)).contains("VARIANT-QUESTION", "EXPLANATION-SECRET");
        assertThat(agentEvents.getFirst().variant()).isEqualTo(VARIANT);
        assertThat(agentEvents.getFirst().answerHidden()).isFalse();
    }

    @Test
    void variantFailureWithoutAnswerSendsFixedNoticeAndRetryNotice() {
        allowVariant();
        when(ai.agentStream(any(), any())).thenAnswer(variantThen(
                new AgentDtos.VariantResult("failed", 1, null, List.of("model_unavailable")),
                meta("variant", false, "failed", true)));

        run(variantRequest());

        assertThat(deltas).contains(AgentRequests.VARIANT_FAILED_NOTICE, AgentRequests.RETRY_NOTICE);
        assertThat(agentEvents.getFirst().variant()).isNull();
        assertThat(agentEvents.getFirst().retryable()).isTrue();
    }

    @Test
    void variantFailureWithGeneralAnswerAddsOnlyRetryNotice() {
        allowVariant();
        when(ai.agentStream(any(), any())).thenAnswer(variantThen(
                new AgentDtos.VariantResult("failed", 3, null, List.of("concept_mismatch")),
                meta("variant", false, "failed", true), "일반 ", "답변"));

        run(variantRequest());

        assertThat(deltas).startsWith("일반 ", "답변");
        assertThat(deltas).contains(AgentRequests.RETRY_NOTICE);
        assertThat(deltas).doesNotContain(AgentRequests.VARIANT_FAILED_NOTICE);
    }

    @Test
    void invalidOriginalHasNoRetryNotice() {
        allowVariant();
        when(ai.agentStream(any(), any())).thenAnswer(variantThen(
                new AgentDtos.VariantResult("failed", 0, null, List.of("invalid_original")),
                meta("variant", false, "failed", false)));

        run(variantRequest());

        assertThat(deltas).doesNotContain(AgentRequests.RETRY_NOTICE);
        assertThat(agentEvents.getFirst().retryable()).isFalse();
    }

    @Test
    void failureAfterVariantLineIsCounted() {
        allowVariant();
        when(ai.agentStream(any(), any())).thenAnswer(inv -> {
            ((AgentListener) inv.getArgument(1))
                    .onVariant(new AgentDtos.VariantResult("failed", 3, null, List.of("concept_mismatch")));
            throw AiUnavailableException.afterOutput();
        });

        assertThatThrownBy(() -> run(variantRequest())).isInstanceOf(AiUnavailableException.class);

        assertThat(hasRoom(quota, USER, AiFeature.CHAT)).isFalse();
    }

    @Test
    void connectionFailureOnVariantTurnIsRefunded() {
        allowVariant();
        when(ai.agentStream(any(), any())).thenThrow(new AiUnavailableException());

        assertThatThrownBy(() -> run(variantRequest())).isInstanceOf(AiUnavailableException.class);

        assertThat(hasRoom(quota, USER, AiFeature.CHAT)).isTrue();
    }

    @Test
    void sameVariantQuizIdRunsAgainOnRetry() {
        quota = AiQuotaFixtures.quota(2, Duration.ofHours(1), clock);
        rebuild(true);
        allowVariant();
        when(ai.agentStream(any(), any())).thenAnswer(variantThen(
                new AgentDtos.VariantResult("failed", 1, null, List.of("model_unavailable")),
                meta("variant", false, "failed", true)));

        run(variantRequest());
        run(variantRequest());

        verify(ai, times(2)).agentStream(any(), any());
    }

    @Test
    void variantAccessIsRecheckedRightBeforeRunning() {
        when(variants.requireVariantOriginal(USER, SESSION, QUIZ)).thenReturn(VariantOriginal.of(QUIZ_ENTITY))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "이미 정답 처리된 문항이에요"));
        OpenedChat ticket = service.openChat(USER, variantRequest());

        assertThatThrownBy(() -> service.chat(USER, variantRequest(), ticket, s -> { }, deltas::add, e -> { }))
                .isInstanceOf(ApiException.class);

        verify(ai, never()).agentStream(any(), any());
        assertThat(hasRoom(quota, USER, AiFeature.CHAT)).isTrue();
    }

    @Test
    void variantWithoutSessionIsBadRequestBeforeReserving() {
        ChatRequest request = new ChatRequest("수학", List.of(new ChatRequest.Message("user", "문제 내줘")), null, QUIZ, null);

        assertThatThrownBy(() -> service.openChat(USER, request))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(hasRoom(quota, USER, AiFeature.CHAT)).isTrue();
    }

    @Test
    void teacherVariantIsForbiddenBeforeReserving() {
        when(sessions.requireActiveRole(USER, SESSION)).thenReturn(SessionRole.TEACHER);

        assertThatThrownBy(() -> service.openChat(USER, variantRequest()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(hasRoom(quota, USER, AiFeature.CHAT)).isTrue();
    }

    @Test
    void variantAccessDenialHappensBeforeReserving() {
        when(variants.requireVariantOriginal(USER, SESSION, QUIZ))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "정답 문항"));

        assertThatThrownBy(() -> service.openChat(USER, variantRequest()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(hasRoom(quota, USER, AiFeature.CHAT)).isTrue();
    }

    // ------------------------------------------------------------------ AC-23 memory assembly

    @Test
    void neverSendsDeletedMemoriesOrInvalidSummaries() {
        memory = (userId, scope, question, history) -> new ConversationMemory.Recall(history, 
                new ConversationMemory.StoredSummary("s_old", USER, scope, "무효 요약", Instant.parse("2026-11-24T00:00:00Z")),
                List.of(new ConversationMemory.StoredMemory("m_ok", USER, scope, "유효 기억", null),
                        new ConversationMemory.StoredMemory("m_del", USER, scope, "삭제 기억", Instant.parse("2026-11-24T00:00:00Z")),
                        new ConversationMemory.StoredMemory("m_personal", USER, AgentDtos.Scope.personal(), "개인 기억", null)));
        rebuild(true);
        when(ai.agentStream(any(), any())).thenAnswer(completes(meta("concept", false, null, false), "답"));

        run(student("range 함수가 뭐예요?"));

        AgentDtos.Context context = sent.getFirst().context();
        assertThat(context.summary()).isNull();
        assertThat(context.memories()).extracting(AgentDtos.Memory::id).containsExactly("m_ok");
    }

    // ------------------------------------------------------------------ AC-26 log

    @Test
    void logsOneTurnLineWithoutQuestionOrSearchQuery(CapturedOutput output) {
        when(ai.agentStream(any(), any())).thenAnswer(needs("SECRET-QUERY-5521", Map.of("v", 1)))
                .thenAnswer(completes(meta("answer_request", false, null, false), "답"));

        run(student(QUESTION));

        assertThat(output.getAll()).contains("kind=student", "searchRounds=1");
        assertThat(output.getAll()).doesNotContain("SECRET-QUESTION-5521", "SECRET-QUERY-5521", "첫 자료");
    }
}
