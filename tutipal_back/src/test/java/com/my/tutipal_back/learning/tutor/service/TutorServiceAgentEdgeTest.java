package com.my.tutipal_back.learning.tutor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
import com.my.tutipal_back.rag.dto.RagHit;
import com.my.tutipal_back.rag.service.RagSearchService;
import com.my.tutipal_back.sessions.dto.ChatSettings;
import com.my.tutipal_back.sessions.entity.SessionRole;
import com.my.tutipal_back.sessions.service.StudySessionService;

/** PT-85 developer edge cases: heartbeat on ping lines, personal agent cannot re-search, stray variant lines. */
class TutorServiceAgentEdgeTest {

    private static final long USER = 2L;
    private static final long SESSION = 10L;

    private final AiClient ai = mock(AiClient.class);
    private final StudySessionService sessions = mock(StudySessionService.class);
    private final RagSearchService rag = mock(RagSearchService.class);
    private final AiSlots slots = mock(AiSlots.class);
    private final AiQuota quota = AiQuotaFixtures.quota(5, Duration.ofHours(1),
            new MutableClock(Instant.parse("2026-11-25T00:00:00Z")));
    private final TutorService service = new TutorService(ai, sessions, rag, slots, quota,
            mock(QuizVariantAccess.class), new AgentContextAssembler(new NoStoredConversationMemory()), true,
            StudentQuestionSink.NONE);
    private final List<String> deltas = new ArrayList<>();
    private final List<AgentDtos.AgentEvent> agentEvents = new ArrayList<>();

    private static final AgentDtos.Meta META = new AgentDtos.Meta("student", "concept", List.of("classify", "respond"),
            1, 1, false, 0, null, false, new AgentDtos.ContextReport(100, 3000,
                    Map.of("messages", 0, "memories", 0, "materials", 0, "screenSummary", 0), List.of()),
            List.of(), null);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void collaborators() {
        when(slots.run(any(Supplier.class))).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
        when(sessions.requireActiveRole(USER, SESSION)).thenReturn(SessionRole.STUDENT);
        when(sessions.chatSettings(USER, SESSION)).thenReturn(new ChatSettings("파이썬", 2, true, true));
        when(rag.search(eq(SESSION), anyString(), anyInt())).thenReturn(List.of(new RagHit("a.pdf", 1, "자료", 0.9)));
    }

    private static ChatRequest personal() {
        return new ChatRequest("수학", List.of(new ChatRequest.Message("user", "미분이 뭐야?")), null, null, null);
    }

    private static ChatRequest student() {
        return new ChatRequest("수학", List.of(new ChatRequest.Message("user", "range가 뭐예요?")), SESSION, null, null);
    }

    @Test
    void heartbeatFailureOnPingStopsTheTurnBeforeAnyDelta() {
        RuntimeException gone = new IllegalStateException("client gone");
        when(ai.agentStream(any(), any())).thenAnswer(inv -> {
            AgentListener listener = inv.getArgument(1);
            listener.onPing();
            listener.onDelta("보내면 안 됨");
            return new AgentOutcome.Completed(META);
        });
        OpenedChat ticket = service.openChat(USER, personal());
        int[] beats = {0};

        assertThatThrownBy(() -> service.chat(USER, personal(), ticket, s -> { }, deltas::add, agentEvents::add, () -> {
            if (++beats[0] > 1) {
                throw gone;                     // first beat: before calling FastAPI, second: the ping line
            }
        })).isSameAs(gone);

        assertThat(deltas).isEmpty();
        assertThat(agentEvents).isEmpty();
    }

    @Test
    void personalAgentNeedSearchIsAnErrorWithoutSearching() {
        when(ai.agentStream(any(), any())).thenReturn(new AgentOutcome.NeedSearch("q1", Map.of("v", 1)));
        OpenedChat ticket = service.openChat(USER, personal());

        assertThatThrownBy(() -> service.chat(USER, personal(), ticket, s -> { }, deltas::add, agentEvents::add))
                .isInstanceOf(RuntimeException.class);

        verify(rag, never()).search(anyLong(), anyString(), anyInt());
        verify(ai, times(1)).agentStream(any(), any());
    }

    @Test
    void variantLineOnATurnThatDidNotAskForOneIsIgnored() {
        when(ai.agentStream(any(), any())).thenAnswer(inv -> {
            AgentListener listener = inv.getArgument(1);
            listener.onVariant(new AgentDtos.VariantResult("ok", 1, new AgentDtos.VariantQuestion("CHOICE",
                    "STRAY-VARIANT", List.of("a", "b"), 0, null, "STRAY-EXPLANATION"), null));
            listener.onStart(List.of());
            listener.onDelta("답");
            return new AgentOutcome.Completed(META);
        });
        OpenedChat ticket = service.openChat(USER, student());

        service.chat(USER, student(), ticket, s -> { }, deltas::add, agentEvents::add);

        assertThat(deltas).containsExactly("답");
        assertThat(agentEvents).singleElement().satisfies(e -> assertThat(e.variant()).isNull());
    }

    @Test
    void sourcesAreSentOnceEvenIfStartRepeats() {
        List<List<String>> sources = new ArrayList<>();
        when(ai.agentStream(any(), any())).thenAnswer(inv -> {
            AgentDtos.AgentChatRequest body = inv.getArgument(0);
            AgentListener listener = inv.getArgument(1);
            listener.onStart(List.of(body.materials().getFirst().id(), "unknown-id"));
            listener.onStart(List.of(body.materials().getFirst().id()));
            listener.onDelta("답");
            return new AgentOutcome.Completed(META);
        });
        OpenedChat ticket = service.openChat(USER, student());

        service.chat(USER, student(), ticket, sources::add, deltas::add, agentEvents::add);

        assertThat(sources).containsExactly(List.of("a.pdf p.1"));
    }
}
