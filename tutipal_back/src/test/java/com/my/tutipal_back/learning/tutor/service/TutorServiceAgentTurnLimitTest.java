package com.my.tutipal_back.learning.tutor.service;

import static com.my.tutipal_back.ai.quota.AiQuotaFixtures.hasRoom;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import com.my.tutipal_back.ai.quota.AiQuotaExceededException;
import com.my.tutipal_back.ai.quota.AiQuotaFixtures;
import com.my.tutipal_back.ai.quota.MutableClock;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.learning.quiz.service.QuizVariantAccess;
import com.my.tutipal_back.learning.tutor.dto.ChatRequest;
import com.my.tutipal_back.rag.dto.RagHit;
import com.my.tutipal_back.rag.service.RagSearchService;
import com.my.tutipal_back.sessions.dto.ChatSettings;
import com.my.tutipal_back.sessions.entity.SessionRole;
import com.my.tutipal_back.sessions.service.StudySessionService;

/**
 * PT-85 Round 1 review: one agent turn per user at a time (S7), the opened chat carries the role and frees its
 * slot on every ending (S2), a single constructor that refuses a half-wired agent (S1), and a log line for
 * failed turns too (S8).
 */
@ExtendWith(OutputCaptureExtension.class)
class TutorServiceAgentTurnLimitTest {

    private static final long USER = 2L;
    private static final long OTHER_USER = 3L;
    private static final long SESSION = 10L;
    private static final String QUESTION = "SECRET-QUESTION-7781 미분이 뭐야?";
    private static final AgentDtos.Meta META = new AgentDtos.Meta("personal", "concept", List.of("classify", "respond"),
            1, 1, false, 0, null, false, new AgentDtos.ContextReport(100, 3000,
                    Map.of("messages", 0, "memories", 0, "materials", 0, "screenSummary", 0), List.of()),
            List.of(), null);

    private final AiClient ai = mock(AiClient.class);
    private final StudySessionService sessions = mock(StudySessionService.class);
    private final RagSearchService rag = mock(RagSearchService.class);
    private final AiSlots slots = mock(AiSlots.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-25T00:00:00Z"));
    private AiQuota quota = AiQuotaFixtures.quota(5, Duration.ofHours(1), clock);
    private TutorService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void collaborators() {
        when(slots.run(any(Supplier.class))).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
        when(sessions.requireActiveRole(USER, SESSION)).thenReturn(SessionRole.STUDENT);
        when(sessions.chatSettings(USER, SESSION)).thenReturn(new ChatSettings("파이썬", 2, true, true));
        when(rag.search(eq(SESSION), anyString(), anyInt())).thenReturn(List.of(new RagHit("a.pdf", 1, "자료", 0.9)));
        when(ai.agentStream(any(), any())).thenAnswer(inv -> {
            AgentListener listener = inv.getArgument(1);
            listener.onDelta("답");
            return new AgentOutcome.Completed(META);
        });
        service = build(true);
    }

    private TutorService build(boolean agentEnabled) {
        return new TutorService(ai, sessions, rag, slots, quota, mock(QuizVariantAccess.class),
                new AgentContextAssembler(new NoStoredConversationMemory()), agentEnabled, StudentQuestionSink.NONE);
    }

    private static ChatRequest personal() {
        return new ChatRequest("수학", List.of(new ChatRequest.Message("user", QUESTION)), null, null, null);
    }

    private static ChatRequest student() {
        return new ChatRequest("수학", List.of(new ChatRequest.Message("user", QUESTION)), SESSION, null, null);
    }

    private static void assertTooManyRequests(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    private void chat(OpenedChat opened, ChatRequest request) {
        service.chat(USER, request, opened, s -> { }, d -> { }, e -> { }, () -> { });
    }

    // ------------------------------------------------------------------ S7 one agent turn per user

    @Test
    void secondAgentTurnOfTheSameUserIs429BeforeReserving() {
        quota = AiQuotaFixtures.quota(2, Duration.ofHours(1), clock);
        service = build(true);
        service.openChat(USER, personal());

        assertTooManyRequests(() -> service.openChat(USER, student()));
        assertThat(hasRoom(quota, USER, AiFeature.CHAT)).isTrue();   // the rejected turn reserved nothing
    }

    @Test
    void slotIsFreedAfterASuccessfulTurn() {
        chat(service.openChat(USER, personal()), personal());

        chat(service.openChat(USER, student()), student());
    }

    @Test
    void slotIsFreedAfterAFailedTurn() {
        doThrow(new AiUnavailableException()).when(ai).agentStream(any(), any());
        OpenedChat first = service.openChat(USER, personal());
        assertThatThrownBy(() -> chat(first, personal())).isInstanceOf(AiUnavailableException.class);

        assertThat(service.openChat(USER, personal())).isNotNull();
    }

    @Test
    void slotIsFreedWhenTheClientStopsTheTurn() {
        RuntimeException gone = new IllegalStateException("client gone");
        OpenedChat first = service.openChat(USER, personal());
        assertThatThrownBy(() -> service.chat(USER, personal(), first, s -> { }, d -> { throw gone; }, e -> { },
                () -> { })).isSameAs(gone);

        assertThat(service.openChat(USER, personal())).isNotNull();
    }

    @Test
    void slotIsFreedWhenThePreparationFailsBeforeTheModelRuns() {
        when(sessions.chatSettings(USER, SESSION)).thenThrow(new ApiException(HttpStatus.GONE, "끝난 수업"));
        OpenedChat first = service.openChat(USER, student());
        assertThatThrownBy(() -> chat(first, student())).isInstanceOf(ApiException.class);

        assertThat(hasRoom(quota, USER, AiFeature.CHAT)).isTrue();
        assertThat(service.openChat(USER, personal())).isNotNull();
    }

    @Test
    void releaseWithoutChatFreesTheSlotAndRefunds() {
        quota = AiQuotaFixtures.quota(1, Duration.ofHours(1), clock);
        service = build(true);
        OpenedChat opened = service.openChat(USER, personal());

        opened.release();
        opened.release();                                              // idempotent

        assertThat(hasRoom(quota, USER, AiFeature.CHAT)).isTrue();
        assertThat(service.openChat(USER, personal())).isNotNull();
    }

    @Test
    void quotaRejectionDoesNotKeepTheSlot() {
        quota = AiQuotaFixtures.quota(1, Duration.ofHours(1), clock);
        service = build(true);
        chat(service.openChat(USER, personal()), personal());
        assertThatThrownBy(() -> service.openChat(USER, personal())).isInstanceOf(AiQuotaExceededException.class);

        clock.advance(Duration.ofHours(2));

        assertThat(service.openChat(USER, personal())).isNotNull();
    }

    @Test
    void otherUsersAreNotBlocked() {
        service.openChat(USER, personal());

        assertThat(service.openChat(OTHER_USER, personal())).isNotNull();
    }

    @Test
    void teacherPreviewIsNotLimited() {
        when(sessions.requireActiveRole(USER, SESSION)).thenReturn(SessionRole.TEACHER);

        service.openChat(USER, student());

        assertThat(service.openChat(USER, student())).isNotNull();
    }

    @Test
    void legacyPathWithAgentOffIsNotLimited() {
        service = build(false);
        service.openChat(USER, personal());

        assertThat(service.openChat(USER, personal())).isNotNull();
    }

    @Test
    void openedChatCarriesTheRoleCheckedInOpenChat() {
        OpenedChat opened = service.openChat(USER, student());

        assertThat(opened.role()).isEqualTo(SessionRole.STUDENT);
        assertThat(service.openChat(OTHER_USER, personal()).role()).isNull();
    }

    // ------------------------------------------------------------------ S1 single constructor

    @Test
    void agentOnWithoutItsCollaboratorsFailsFastInsteadOfSilentlyTurningOff() {
        assertThatThrownBy(() -> new TutorService(ai, sessions, rag, slots, quota, null, null, true, StudentQuestionSink.NONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new TutorService(ai, sessions, rag, slots, quota, null, null, false, StudentQuestionSink.NONE)).isNotNull();
    }

    // ------------------------------------------------------------------ S8 failed turns are logged too

    @Test
    void successfulTurnLogsResultOk(CapturedOutput output) {
        chat(service.openChat(USER, personal()), personal());

        assertThat(output.getAll()).contains("agent turn kind=personal result=ok");
    }

    @Test
    void fastApiFailureLogsOneErrorLineWithoutTheQuestion(CapturedOutput output) {
        doThrow(new AiUnavailableException()).when(ai).agentStream(any(), any());
        OpenedChat opened = service.openChat(USER, personal());
        assertThatThrownBy(() -> chat(opened, personal())).isInstanceOf(AiUnavailableException.class);

        assertThat(output.getAll()).contains("agent turn kind=personal result=error reason=ai_unavailable",
                "tools=answer_question");
        assertThat(output.getAll()).doesNotContain("SECRET-QUESTION-7781");
    }

    @Test
    void protocolViolationLogsTheStatus(CapturedOutput output) {
        doReturn(new AgentOutcome.NeedSearch("다시 찾기", Map.of("v", 1))).when(ai).agentStream(any(), any());
        OpenedChat opened = service.openChat(USER, personal());
        assertThatThrownBy(() -> chat(opened, personal())).isInstanceOf(ApiException.class);

        assertThat(output.getAll()).contains("kind=personal result=error reason=status_502");
        assertThat(output.getAll()).doesNotContain("다시 찾기");
    }

    @Test
    void lostAccessBeforeTheModelRunsIsLoggedToo(CapturedOutput output) {
        when(sessions.chatSettings(USER, SESSION)).thenThrow(new ApiException(HttpStatus.GONE, "끝난 수업"));
        OpenedChat opened = service.openChat(USER, student());
        assertThatThrownBy(() -> chat(opened, student())).isInstanceOf(ApiException.class);

        assertThat(output.getAll()).contains("kind=student result=error reason=status_410", "searchRounds=0");
    }
}
