package com.my.tutipal_back.learning.tutor.service;

import static com.my.tutipal_back.ai.quota.AiQuotaFixtures.hasRoom;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.ai.client.AiSlots;
import com.my.tutipal_back.ai.client.AiUnavailableException;
import com.my.tutipal_back.ai.quota.AiFeature;
import com.my.tutipal_back.ai.quota.AiQuota;
import com.my.tutipal_back.ai.quota.AiQuotaExceededException;
import com.my.tutipal_back.ai.quota.AiQuotaFixtures;
import com.my.tutipal_back.ai.quota.MutableClock;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.learning.tutor.dto.ChatRequest;
import com.my.tutipal_back.rag.dto.RagHit;
import com.my.tutipal_back.rag.service.RagSearchService;
import com.my.tutipal_back.sessions.dto.ChatSettings;
import com.my.tutipal_back.sessions.service.StudySessionService;

/** PT-97 S3/S4/S6: one user question = one CHAT, reserved before the stream opens. */
class TutorServiceQuotaTest {

    private static final long USER = 2L;
    private static final long SESSION = 10L;
    private static final ChatRequest PERSONAL = new ChatRequest("수학",
            List.of(new ChatRequest.Message("user", "미분이 뭐야?")), null);
    private static final ChatRequest IN_SESSION = new ChatRequest("수학",
            List.of(new ChatRequest.Message("user", "미분이 뭐야?")), SESSION);

    private final AiClient ai = mock(AiClient.class);
    private final StudySessionService sessions = mock(StudySessionService.class);
    private final RagSearchService rag = mock(RagSearchService.class);
    private final AiSlots slots = mock(AiSlots.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-25T00:00:00Z"));
    private AiQuota quota = AiQuotaFixtures.quota(1, Duration.ofHours(1), clock);
    private TutorService service = new TutorService(ai, sessions, rag, slots, quota, null, null, false);

    private final List<String> deltas = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void collaborators() {
        when(slots.run(any(Supplier.class))).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
        when(slots.run(any(Duration.class), any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        when(sessions.chatSettings(USER, SESSION)).thenReturn(new ChatSettings("수학", 3, false, false));
        when(rag.search(eq(SESSION), anyString(), anyInt())).thenReturn(List.of());
    }

    private void modelStreams(String... pieces) {
        doAnswer(inv -> {
            Consumer<String> onDelta = inv.getArgument(2);
            for (String p : pieces) {
                onDelta.accept(p);
            }
            return null;
        }).when(ai).chatStream(anyString(), anyList(), any());
    }

    // S4
    @Test
    void personalChatReservesChatWhenOpened() {
        OpenedChat ticket = service.openChat(USER, PERSONAL);

        assertThat(ticket).isNotNull();
        assertThat(hasRoom(quota, USER, AiFeature.CHAT)).isFalse();
        verifyNoInteractions(sessions);
    }

    @Test
    void sessionChatChecksAccessThenReservesChat() {
        OpenedChat ticket = service.openChat(USER, IN_SESSION);

        assertThat(ticket).isNotNull();
        verify(sessions).requireActiveRole(USER, SESSION);
        assertThat(hasRoom(quota, USER, AiFeature.CHAT)).isFalse();
    }

    @Test
    void deniedSessionChatDoesNotReserve() {
        when(sessions.requireActiveRole(USER, SESSION)).thenThrow(new ApiException(HttpStatus.GONE, "끝난 세션"));

        assertThatThrownBy(() -> service.openChat(USER, IN_SESSION)).isInstanceOf(ApiException.class);

        assertThat(hasRoom(quota, USER, AiFeature.CHAT)).isTrue();
    }

    @Test
    void openChatOverQuotaThrowsForBothPaths() {
        service.openChat(USER, PERSONAL);

        assertThatThrownBy(() -> service.openChat(USER, PERSONAL))
                .isInstanceOfSatisfying(AiQuotaExceededException.class,
                        e -> assertThat(e.getFeature()).isEqualTo(AiFeature.CHAT));
        assertThatThrownBy(() -> service.openChat(USER, IN_SESSION))
                .isInstanceOf(AiQuotaExceededException.class);
    }

    @Test
    void oneQuestionCountsExactlyOneChat() {
        quota = AiQuotaFixtures.quota(2, Duration.ofHours(1), clock);
        service = new TutorService(ai, sessions, rag, slots, quota, null, null, false);
        modelStreams("미분은 ", "변화율이에요");

        service.chat(USER, IN_SESSION, service.openChat(USER, IN_SESSION), s -> { }, deltas::add);

        assertThat(deltas).containsExactly("미분은 ", "변화율이에요");
        quota.reserve(USER, AiFeature.CHAT);
        assertThat(hasRoom(quota, USER, AiFeature.CHAT)).isFalse();
    }

    @Test
    void successfulChatIsCounted() {
        modelStreams("답");
        service.chat(USER, PERSONAL, service.openChat(USER, PERSONAL), s -> { }, deltas::add);

        assertThat(deltas).containsExactly("답");
        assertThat(hasRoom(quota, USER, AiFeature.CHAT)).isFalse();
    }

    // S3
    @Test
    void clientCancelAfterStreamStartIsCounted() {
        modelStreams("첫 조각", "둘째 조각");
        var gone = new RuntimeException("client gone");
        OpenedChat ticket = service.openChat(USER, PERSONAL);

        assertThatThrownBy(() -> service.chat(USER, PERSONAL, ticket, s -> { }, d -> { throw gone; }))
                .isSameAs(gone);

        assertThat(hasRoom(quota, USER, AiFeature.CHAT)).isFalse();
    }

    // Round 1 (린 주요): AiUnavailable after the model already streamed output is counted, so an endless-generation
    // prompt that hits the AI-side timeout cannot hold a GPU slot for free. Replaces aiUnavailableMidStreamIsRefunded.
    @Test
    void aiUnavailableMidStreamIsCounted() {
        doAnswer(inv -> {
            Consumer<String> onDelta = inv.getArgument(2);
            onDelta.accept("반쯤");
            throw AiUnavailableException.afterOutput();
        }).when(ai).chatStream(anyString(), anyList(), any());
        OpenedChat ticket = service.openChat(USER, PERSONAL);

        assertThatThrownBy(() -> service.chat(USER, PERSONAL, ticket, s -> { }, deltas::add))
                .isInstanceOf(AiUnavailableException.class);

        assertThat(deltas).containsExactly("반쯤");
        assertThat(hasRoom(quota, USER, AiFeature.CHAT)).isFalse();
    }

    @Test
    void aiUnavailableBeforeFirstOutputIsRefunded() {
        doAnswer(inv -> {
            throw new AiUnavailableException();
        }).when(ai).chatStream(anyString(), anyList(), any());
        OpenedChat ticket = service.openChat(USER, PERSONAL);

        assertThatThrownBy(() -> service.chat(USER, PERSONAL, ticket, s -> { }, deltas::add))
                .isInstanceOf(AiUnavailableException.class);

        assertThat(hasRoom(quota, USER, AiFeature.CHAT)).isTrue();
    }

    @Test
    void clientGoneWhileSendingSourcesIsCounted() {
        when(rag.search(eq(SESSION), anyString(), anyInt()))
                .thenReturn(List.of(new RagHit("a.pdf", 1, "미분 자료", 0.9)));
        var gone = new IllegalStateException("client gone");
        OpenedChat ticket = service.openChat(USER, IN_SESSION);

        assertThatThrownBy(() -> service.chat(USER, IN_SESSION, ticket, s -> { throw gone; }, deltas::add))
                .isSameAs(gone);

        assertThat(hasRoom(quota, USER, AiFeature.CHAT)).isFalse();
        verify(ai, never()).chatStream(anyString(), anyList(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void slotWaitTimeoutIsRefunded() {
        var busy = new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "지금 질문이 많아요");
        when(slots.run(any(Supplier.class))).thenThrow(busy);
        when(slots.run(any(Duration.class), any(Supplier.class))).thenThrow(busy);
        OpenedChat ticket = service.openChat(USER, PERSONAL);

        assertThatThrownBy(() -> service.chat(USER, PERSONAL, ticket, s -> { }, deltas::add)).isSameAs(busy);

        assertThat(hasRoom(quota, USER, AiFeature.CHAT)).isTrue();
    }

    @Test
    void ragFailureBeforeModelIsRefunded() {
        when(rag.search(eq(SESSION), anyString(), anyInt())).thenThrow(new IllegalStateException("vector db down"));
        OpenedChat ticket = service.openChat(USER, IN_SESSION);

        assertThatThrownBy(() -> service.chat(USER, IN_SESSION, ticket, s -> { }, deltas::add))
                .isInstanceOf(IllegalStateException.class);

        assertThat(hasRoom(quota, USER, AiFeature.CHAT)).isTrue();
        verify(ai, never()).chatStream(anyString(), anyList(), any());
    }
}
