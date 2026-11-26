package com.my.tutipal_back.learning.tutor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.my.tutipal_back.ai.client.AgentListener;
import com.my.tutipal_back.ai.client.AgentOutcome;
import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.ai.client.AiSlots;
import com.my.tutipal_back.ai.dto.AgentDtos;
import com.my.tutipal_back.ai.quota.AiQuota;
import com.my.tutipal_back.ai.quota.AiQuotaFixtures;
import com.my.tutipal_back.ai.quota.MutableClock;
import com.my.tutipal_back.learning.quiz.service.QuizVariantAccess;
import com.my.tutipal_back.learning.tutor.dto.ChatRequest;
import com.my.tutipal_back.rag.service.RagSearchService;
import com.my.tutipal_back.sessions.dto.ChatSettings;
import com.my.tutipal_back.sessions.entity.SessionRole;
import com.my.tutipal_back.sessions.service.StudySessionService;

/**
 * PT-87 AC-6 (TutorService side): the student question is handed to StudentQuestionSink at the start of
 * chat(..., heartbeat), after access check and CHAT reservation, for both the agent and the legacy path.
 * Only STUDENT turns with a sessionId, without variantQuizId, whose last message is the user's.
 * Assumes the new constructor parameter StudentQuestionSink is the last one.
 */
@ExtendWith(OutputCaptureExtension.class)
class TutorServiceQuestionSinkTest {

    private static final long USER = 2L;
    private static final long SESSION = 10L;
    private static final long QUIZ = 77L;
    private static final String MARKER = "SECRET-Q-3141 for문이 안 끝나요";

    private final AiClient ai = mock(AiClient.class);
    private final StudySessionService sessions = mock(StudySessionService.class);
    private final RagSearchService rag = mock(RagSearchService.class);
    private final AiSlots slots = mock(AiSlots.class);
    private final QuizVariantAccess variants = mock(QuizVariantAccess.class);
    private final StudentQuestionSink sink = mock(StudentQuestionSink.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-25T00:00:00Z"));
    private final AiQuota quota = AiQuotaFixtures.quota(10, Duration.ofHours(1), clock);
    private final List<String> deltas = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void collaborators() {
        when(slots.run(any(Supplier.class))).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
        when(slots.run(any(Duration.class), any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        when(sessions.requireActiveRole(USER, SESSION)).thenReturn(SessionRole.STUDENT);
        when(sessions.chatSettings(USER, SESSION)).thenReturn(new ChatSettings("파이썬", 2, false, false));
        when(rag.search(eq(SESSION), anyString(), anyInt())).thenReturn(List.of());
        doAnswer(inv -> {
            Consumer<String> onDelta = inv.getArgument(2);
            onDelta.accept("답변");
            return null;
        }).when(ai).chatStream(anyString(), anyList(), any());
    }

    private TutorService legacy() {
        return new TutorService(ai, sessions, rag, slots, quota, null, null, false, sink);
    }

    private TutorService agent() {
        return new TutorService(ai, sessions, rag, slots, quota, variants,
                new AgentContextAssembler(new NoStoredConversationMemory()), true, sink);
    }

    private static ChatRequest inSession(String... userThenAssistant) {
        List<ChatRequest.Message> messages = new ArrayList<>();
        for (int i = 0; i < userThenAssistant.length; i++) {
            messages.add(new ChatRequest.Message(i % 2 == 0 ? "user" : "assistant", userThenAssistant[i]));
        }
        return new ChatRequest("파이썬", messages, SESSION, null, null);
    }

    private void run(TutorService service, ChatRequest request) {
        OpenedChat opened = service.openChat(USER, request);
        service.chat(USER, request, opened, s -> { }, deltas::add, e -> { }, () -> { });
    }

    private static AgentDtos.Meta meta() {
        return new AgentDtos.Meta("student", "concept", List.of("classify", "compose", "respond"), 1, 1, false, 0, null,
                false, new AgentDtos.ContextReport(500, 3000,
                        Map.of("messages", 0, "memories", 0, "materials", 0, "screenSummary", 0), List.of()),
                List.of(), null);
    }

    // ------------------------------------------------------------------ recorded

    @Test
    void legacyStudentTurnRecordsTheLastUserMessage() {
        run(legacy(), inSession("range가 뭐예요?", "0부터 세요", MARKER));

        verify(sink).record(USER, SESSION, MARKER);
        assertThat(deltas).containsExactly("답변");
    }

    @Test
    void agentStudentTurnRecordsTheQuestionToo() {
        when(ai.agentStream(any(), any())).thenAnswer(inv -> {
            AgentListener listener = inv.getArgument(1);
            listener.onStart(List.of());
            listener.onDelta("힌트");
            return new AgentOutcome.Completed(meta());
        });

        run(agent(), inSession(MARKER));

        verify(sink).record(USER, SESSION, MARKER);
        assertThat(deltas).contains("힌트");
    }

    @Test
    void questionIsRecordedEvenIfTheAnswerFailsLater() {
        when(rag.search(eq(SESSION), anyString(), anyInt())).thenThrow(new IllegalStateException("vector db down"));
        TutorService service = legacy();
        ChatRequest request = inSession(MARKER);
        OpenedChat opened = service.openChat(USER, request);

        assertThatThrownBy(() -> service.chat(USER, request, opened, s -> { }, deltas::add, e -> { }, () -> { }))
                .isInstanceOf(IllegalStateException.class);

        verify(sink).record(USER, SESSION, MARKER);
    }

    // ------------------------------------------------------------------ not recorded

    @Test
    void teacherTurnIsNotRecorded() {
        when(sessions.requireActiveRole(USER, SESSION)).thenReturn(SessionRole.TEACHER);

        run(legacy(), inSession(MARKER));
        run(agent(), inSession(MARKER));

        verify(sink, never()).record(anyLong(), anyLong(), anyString());
    }

    @Test
    void personalChatIsNotRecorded() {
        ChatRequest personal = new ChatRequest("수학", List.of(new ChatRequest.Message("user", MARKER)), null);

        run(legacy(), personal);

        verify(sink, never()).record(anyLong(), anyLong(), anyString());
    }

    @Test
    void variantTurnIsNotRecorded() {
        ChatRequest variant = new ChatRequest("수학", List.of(new ChatRequest.Message("user", "비슷한 문제 내줘 " + MARKER)),
                SESSION, QUIZ, null);

        run(legacy(), variant);

        verify(sink, never()).record(anyLong(), anyLong(), anyString());
    }

    @Test
    void turnEndingWithAnAssistantMessageIsNotRecorded() {
        run(legacy(), inSession(MARKER, "앞선 답변"));

        verify(sink, never()).record(anyLong(), anyLong(), anyString());
    }

    // ------------------------------------------------------------------ failure isolation

    @Test
    void sinkFailureDoesNotStopTheChatAndIsNotLoggedWithTheText(CapturedOutput output) {
        doThrow(new IllegalStateException("could not save " + MARKER)).when(sink).record(anyLong(), anyLong(), anyString());

        assertThatCode(() -> run(legacy(), inSession(MARKER))).doesNotThrowAnyException();

        assertThat(deltas).containsExactly("답변");
        assertThat(output.getAll()).doesNotContain("SECRET-Q-3141");
    }

    @Test
    void noneSinkDoesNothing() {
        assertThatCode(() -> StudentQuestionSink.NONE.record(USER, SESSION, MARKER)).doesNotThrowAnyException();
    }
}
