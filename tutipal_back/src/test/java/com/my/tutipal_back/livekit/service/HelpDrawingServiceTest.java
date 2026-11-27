package com.my.tutipal_back.livekit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.livekit.repository.HelpRequestRepository;
import com.my.tutipal_back.livekit.service.HelpDrawingService.DrawEvent;
import com.my.tutipal_back.sessions.service.StudySessionService;

class HelpDrawingServiceTest {

    private final HelpRequestRepository helps = mock(HelpRequestRepository.class);
    private final ScreenShareService shares = mock(ScreenShareService.class);
    private final StudySessionService sessions = mock(StudySessionService.class);
    private final SimpMessagingTemplate broker = mock(SimpMessagingTemplate.class);
    private final HelpDrawingService service = new HelpDrawingService(helps, shares, sessions, broker);

    @BeforeEach
    void allowAcceptedHelp() {
        when(helps.sessionIdOf(2L)).thenReturn(Optional.of(3L));
        when(shares.drawingRecipient(1L, 2L)).thenReturn(4L);
    }

    @Test
    void publishesInOrderToTheStudentAfterTakingTheClassLockAndCheckingPermission() {
        Map<String, Object> add = Map.of("k", "add", "id", "line", "tool", "hl", "c", "#facc15",
                "p", List.of(List.of(0, 1)));
        Map<String, Object> clear = Map.of("k", "clear");

        service.publish(1L, 2L, add);
        service.publish(1L, 2L, clear);

        InOrder order = inOrder(sessions, shares, broker);
        order.verify(sessions).lockForHelp(3L);
        order.verify(shares).drawingRecipient(1L, 2L);
        order.verify(broker).convertAndSendToUser("4", "/queue/draw", new DrawEvent(2L, 1, add));
        order.verify(sessions).lockForHelp(3L);
        order.verify(shares).drawingRecipient(1L, 2L);
        order.verify(broker).convertAndSendToUser("4", "/queue/draw", new DrawEvent(2L, 2, clear));
    }

    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"FORBIDDEN", "NOT_FOUND", "GONE", "CONFLICT"})
    void deniedOrClosedHelpProducesNoEvent(HttpStatus status) {
        when(shares.drawingRecipient(1L, 2L)).thenThrow(new ApiException(status, "unavailable"));
        assertThatThrownBy(() -> service.publish(1L, 2L, Map.of("k", "clear")))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(status));
        verifyNoInteractions(broker);
    }

    @ParameterizedTest
    @MethodSource("invalidDrawings")
    void malformedDrawingIsRejectedWithoutBroadcasting(Map<String, Object> drawing) {
        assertThatThrownBy(() -> service.publish(1L, 2L, drawing))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(broker);
    }

    static Stream<Map<String, Object>> invalidDrawings() {
        return Stream.of(
                Map.of("k", "add", "id", "line", "c", "#facc15", "p", List.of()),
                Map.of("k", "add", "id", "line", "tool", "pen", "p", List.of()),
                Map.of("k", "add", "id", "line", "tool", "pen", "c", "#facc15", "p", List.of(List.of(-1, 1))),
                Map.of("k", "del", "ids", List.of(2)),
                Map.of("k", "clear", "extra", true));
    }
}
