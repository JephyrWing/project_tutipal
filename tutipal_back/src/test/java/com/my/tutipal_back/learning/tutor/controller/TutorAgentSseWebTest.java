package com.my.tutipal_back.learning.tutor.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.my.tutipal_back.ai.client.AgentListener;
import com.my.tutipal_back.ai.client.AgentOutcome;
import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.ai.client.AiSlots;
import com.my.tutipal_back.ai.dto.AgentDtos;
import com.my.tutipal_back.ai.quota.AiFeature;
import com.my.tutipal_back.ai.quota.AiQuota;
import com.my.tutipal_back.ai.quota.AiQuotaFixtures;
import com.my.tutipal_back.ai.quota.MutableClock;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.common.exception.GlobalExceptionHandler;
import com.my.tutipal_back.learning.quiz.dto.VariantOriginal;
import com.my.tutipal_back.learning.quiz.entity.Quiz;
import com.my.tutipal_back.learning.quiz.entity.QuizType;
import com.my.tutipal_back.learning.quiz.service.QuizVariantAccess;
import com.my.tutipal_back.learning.tutor.service.AgentContextAssembler;
import com.my.tutipal_back.learning.tutor.service.NoStoredConversationMemory;
import com.my.tutipal_back.learning.tutor.service.TutorService;
import com.my.tutipal_back.rag.dto.RagHit;
import com.my.tutipal_back.rag.service.RagSearchService;
import com.my.tutipal_back.sessions.dto.ChatSettings;
import com.my.tutipal_back.sessions.entity.SessionRole;
import com.my.tutipal_back.sessions.service.StudySessionService;
import com.my.tutipal_back.learning.tutor.service.StudentQuestionSink;

/**
 * PT-85 AC-8 / AC-9 / AC-12 / AC-16 / AC-18 over HTTP: SSE stays sources -> delta -> agent -> done, pings stay
 * internal, hidden answers never reach the body, and every rejection is an HTTP error before the quota is touched.
 */
class TutorAgentSseWebTest {

    private static final Pattern EVENT = Pattern.compile("^event:\\s?(\\S+)$", Pattern.MULTILINE);
    private static final AgentDtos.Meta META = new AgentDtos.Meta("student", "concept", List.of("classify", "respond"),
            1, 1, false, 0, null, false, new AgentDtos.ContextReport(100, 3000,
                    Map.of("messages", 0, "memories", 0, "materials", 0, "screenSummary", 0), List.of()),
            List.of(), null);

    private final StudySessionService sessions = mock(StudySessionService.class);
    private final RagSearchService rag = mock(RagSearchService.class);
    private final AiClient ai = mock(AiClient.class);
    private final AiSlots slots = mock(AiSlots.class);
    private final QuizVariantAccess variants = mock(QuizVariantAccess.class);
    private final AiQuota quota = AiQuotaFixtures.quota(1, Duration.ofHours(1),
            new MutableClock(Instant.parse("2026-11-25T00:00:00Z")));
    private MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        var principal = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")), Map.of("id", "2"), "id");
        var login = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(login);
        when(slots.run(any(Supplier.class))).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
        when(slots.run(any(Duration.class), any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        when(sessions.requireActiveRole(2L, 10L)).thenReturn(SessionRole.STUDENT);
        when(sessions.chatSettings(2L, 10L)).thenReturn(new ChatSettings("파이썬", 2, true, true));
        when(rag.search(eq(10L), anyString(), anyInt())).thenReturn(List.of(new RagHit("a.pdf", 1, "자료", 0.9)));
        TutorService tutor = new TutorService(ai, sessions, rag, slots, quota, variants,
                new AgentContextAssembler(new NoStoredConversationMemory()), true, StudentQuestionSink.NONE);
        mvc = MockMvcBuilders.standaloneSetup(new TutorController(tutor))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearLogin() {
        SecurityContextHolder.clearContext();
    }

    private String stream(String json) throws Exception {
        MvcResult result = mvc.perform(post("/api/tutor/chat").accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(5_000);
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static List<String> eventNames(String body) {
        Matcher m = EVENT.matcher(body);
        List<String> names = new java.util.ArrayList<>();
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    @Test
    void studentStreamIsSourcesDeltaAgentDoneAndPingsStayInternal() throws Exception {
        when(ai.agentStream(any(), any())).thenAnswer(inv -> {
            AgentDtos.AgentChatRequest body = inv.getArgument(0);
            AgentListener listener = inv.getArgument(1);
            listener.onPing();
            listener.onStart(List.of(body.materials().getFirst().id()));
            listener.onDelta("a");
            listener.onPing();
            listener.onDelta("b");
            return new AgentOutcome.Completed(META);
        });

        String body = stream("""
                {"topic":"수학","sessionId":10,"messages":[{"role":"user","content":"range 함수가 뭐예요?"}]}
                """);

        assertThat(eventNames(body)).containsExactly("sources", "delta", "delta", "agent", "done");
        assertThat(body).contains("a.pdf p.1");
        assertThat(body).doesNotContain("ping", "materialIds", "\"meta\"", "nodes");
        assertThat(body).contains("\"suggestHelp\":false", "\"answerHidden\":true", "\"helpDraft\":null");
    }

    @Test
    void agentEventAddsHelpDraftOnlyWhenHelpIsSuggested() throws Exception {
        AgentDtos.Meta helpMeta = new AgentDtos.Meta("student", "code_debug", List.of("classify", "respond"),
                1, 3, true, 0, null, false, META.context(), List.of(), null);
        when(ai.agentStream(any(), any())).thenAnswer(inv -> {
            AgentListener listener = inv.getArgument(1);
            listener.onDelta("힌트");
            return new AgentOutcome.Completed(helpMeta);
        });

        String body = stream("""
                {"topic":"수학","sessionId":10,"messages":[{"role":"user","content":"SECRET-Q-77 에러가 계속 나요"}]}
                """);

        assertThat(eventNames(body)).endsWith("agent", "done");
        assertThat(body).contains("\"suggestHelp\":true", "\"route\":\"code_debug\"", "\"answerHidden\":true",
                "\"helpDraft\":\"[AI 조교 요약] 수업 주제: 파이썬 · 질문 종류: 코드 오류 · 받은 힌트: 3단계까지");
        assertThat(body.substring(body.indexOf("\"helpDraft\""))).doesNotContain("SECRET-Q-77");
    }

    @Test
    void teacherStreamKeepsTheOldContractWithoutAgentEvent() throws Exception {
        when(sessions.requireActiveRole(2L, 10L)).thenReturn(SessionRole.TEACHER);
        doAnswer(inv -> {
            Consumer<String> onDelta = inv.getArgument(2);
            onDelta.accept("미리보기");
            return null;
        }).when(ai).chatStream(anyString(), anyList(), any());

        String body = stream("""
                {"topic":"수학","sessionId":10,"messages":[{"role":"user","content":"설명해 주세요"}]}
                """);

        assertThat(eventNames(body)).containsExactly("sources", "delta", "done");
    }

    @Test
    void agentErrorBecomesSseErrorEvent() throws Exception {
        when(ai.agentStream(any(), any())).thenThrow(new com.my.tutipal_back.ai.client.AiUnavailableException());

        String body = stream("""
                {"topic":"수학","messages":[{"role":"user","content":"미분이 뭐야?"}]}
                """);

        assertThat(eventNames(body)).containsExactly("error");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CHOICE", "TEXT"})
    void hiddenVariantAnswersNeverAppearAnywhereInTheStream(String type) throws Exception {
        when(variants.requireVariantOriginal(2L, 10L, 77L)).thenReturn(VariantOriginal.of(new Quiz(10L, QuizType.CHOICE, "2 + 3 은?",
                List.of("4", "5"), 1, null, Instant.parse("2026-11-24T00:00:00Z"))));
        AgentDtos.VariantQuestion variant = "CHOICE".equals(type)
                ? new AgentDtos.VariantQuestion("CHOICE", "VARIANT-Q 4 + 4 는?", List.of("7", "8"), 1, null,
                        "EXPLANATION-SECRET")
                : new AgentDtos.VariantQuestion("TEXT", "VARIANT-Q 미분의 뜻은?", null, null, "MODEL-ANSWER-SECRET",
                        "EXPLANATION-SECRET");
        when(ai.agentStream(any(), any())).thenAnswer(inv -> {
            ((AgentListener) inv.getArgument(1)).onVariant(new AgentDtos.VariantResult("ok", 1, variant, null));
            return new AgentOutcome.Completed(new AgentDtos.Meta("student", "variant", List.of("classify", "variant"),
                    0, null, false, 0, "ok", false, META.context(), List.of(), null));
        });

        String body = stream("""
                {"topic":"수학","sessionId":10,"variantQuizId":77,"messages":[{"role":"user","content":"비슷한 문제"}]}
                """);

        List<String> names = eventNames(body);
        assertThat(names).doesNotContain("sources", "error").startsWith("delta").endsWith("agent", "done");
        assertThat(body).contains("VARIANT-Q");
        assertThat(body).doesNotContain("EXPLANATION-SECRET", "MODEL-ANSWER-SECRET", "\"answerIndex\":1",
                "\"answerIndex\": 1");
    }

    // ------------------------------------------------------------------ rejections are HTTP, before the quota

    private void assertRejectedBeforeStream(String json, int status) throws Exception {
        mvc.perform(post("/api/tutor/chat").accept(MediaType.TEXT_EVENT_STREAM).contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().is(status))
                .andExpect(request().asyncNotStarted());
        verifyNoInteractions(ai);
        assertThat(AiQuotaFixtures.hasRoom(quota, 2L, AiFeature.CHAT)).isTrue();
    }

    @Test
    void variantWithoutSessionIs400() throws Exception {
        assertRejectedBeforeStream("""
                {"topic":"수학","variantQuizId":77,"messages":[{"role":"user","content":"문제 내줘"}]}
                """, 400);
        verifyNoInteractions(variants);
    }

    @Test
    void teacherVariantIs403() throws Exception {
        when(sessions.requireActiveRole(2L, 10L)).thenReturn(SessionRole.TEACHER);

        assertRejectedBeforeStream("""
                {"topic":"수학","sessionId":10,"variantQuizId":77,"messages":[{"role":"user","content":"문제 내줘"}]}
                """, 403);
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 409})
    void variantAccessDenialIsHttpError(int status) throws Exception {
        when(variants.requireVariantOriginal(2L, 10L, 77L))
                .thenThrow(new ApiException(HttpStatus.valueOf(status), "변형할 수 없는 문항이에요"));

        assertRejectedBeforeStream("""
                {"topic":"수학","sessionId":10,"variantQuizId":77,"messages":[{"role":"user","content":"문제 내줘"}]}
                """, status);
        verifyNoInteractions(rag);
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 410})
    void sessionDenialIsHttpErrorBeforeSearch(int status) throws Exception {
        when(sessions.requireActiveRole(2L, 10L)).thenThrow(new ApiException(HttpStatus.valueOf(status), "접근 불가"));

        assertRejectedBeforeStream("""
                {"topic":"수학","sessionId":10,"messages":[{"role":"user","content":"설명해 주세요"}]}
                """, status);
        verifyNoInteractions(rag);
    }

    @Test
    void lastMessageFromAssistantIs400() throws Exception {
        assertRejectedBeforeStream("""
                {"topic":"수학","messages":[{"role":"user","content":"질문"},{"role":"assistant","content":"답"}]}
                """, 400);
    }

    @Test
    void screenSummaryOver4000CharactersIs400() throws Exception {
        assertRejectedBeforeStream("{\"topic\":\"수학\",\"screenSummary\":\"" + "x".repeat(4001)
                + "\",\"messages\":[{\"role\":\"user\",\"content\":\"질문\"}]}", 400);
    }
}
