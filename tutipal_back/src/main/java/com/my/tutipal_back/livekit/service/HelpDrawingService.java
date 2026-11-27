package com.my.tutipal_back.livekit.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.livekit.repository.HelpRequestRepository;
import com.my.tutipal_back.sessions.service.StudySessionService;

@Service
public class HelpDrawingService {

    private static final Set<String> COLORS = Set.of("#ef4444", "#2563eb", "#facc15");

    private final HelpRequestRepository helps;
    private final ScreenShareService shares;
    private final StudySessionService sessions;
    private final SimpMessagingTemplate broker;
    private final AtomicLong sequence = new AtomicLong();

    public HelpDrawingService(HelpRequestRepository helps, ScreenShareService shares,
                              StudySessionService sessions, SimpMessagingTemplate broker) {
        this.helps = helps;
        this.shares = shares;
        this.sessions = sessions;
        this.broker = broker;
    }

    public record DrawEvent(Long helpId, long sequence, Map<String, Object> message) {}

    @Transactional
    public void publish(Long teacherId, Long helpId, Map<String, Object> message) {
        Long sessionId = helps.sessionIdOf(helpId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "도움 요청을 찾을 수 없어요"));
        sessions.lockForHelp(sessionId);
        Long recipient = shares.drawingRecipient(teacherId, helpId);
        if (!valid(message)) throw new ApiException(HttpStatus.BAD_REQUEST, "잘못된 판서 데이터입니다");

        // Keep the lock through publication so an end or removal cannot commit between authorization and publication.
        broker.convertAndSendToUser(recipient.toString(), "/queue/draw",
                new DrawEvent(helpId, sequence.incrementAndGet(), message));
    }

    private static boolean valid(Map<String, Object> message) {
        if (message == null) return false;
        if ("clear".equals(message.get("k"))) return message.size() == 1;
        if ("del".equals(message.get("k"))) {
            return message.size() == 2 && message.get("ids") instanceof List<?> ids && ids.size() <= 500
                    && ids.stream().allMatch(id -> id instanceof String string && string.length() <= 40);
        }
        Object tool = message.get("tool");
        return "add".equals(message.get("k")) && message.size() == 5
                && message.get("id") instanceof String id && id.length() <= 40
                && ("pen".equals(tool) || "hl".equals(tool))
                && message.get("c") instanceof String color && COLORS.contains(color)
                && message.get("p") instanceof List<?> points && points.size() <= 500
                && points.stream().allMatch(point -> point instanceof List<?> xy && xy.size() == 2
                        && xy.stream().allMatch(HelpDrawingService::validCoordinate));
    }

    private static boolean validCoordinate(Object coordinate) {
        if (!(coordinate instanceof Number number)) return false;
        double value = number.doubleValue();
        return value >= 0 && value <= 1;
    }
}
