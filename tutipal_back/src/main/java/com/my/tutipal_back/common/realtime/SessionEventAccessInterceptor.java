package com.my.tutipal_back.common.realtime;

import java.security.Principal;

import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.stereotype.Component;

import com.my.tutipal_back.common.realtime.StompAuthInterceptor.SessionTopic;
import com.my.tutipal_back.sessions.entity.SessionRole;
import com.my.tutipal_back.sessions.service.StudySessionService;

/** 이미 구독한 연결도 세션 알림을 전달할 때 현재 참가 권한을 확인한다. */
@Component
public class SessionEventAccessInterceptor implements ExecutorChannelInterceptor {

    private final StudySessionService sessions;
    private final SessionWebSocketGuard socketGuard;

    public SessionEventAccessInterceptor(@Lazy StudySessionService sessions, SessionWebSocketGuard socketGuard) {
        this.sessions = sessions;
        this.socketGuard = socketGuard;
    }

    @Override
    public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
        var headers = message.getHeaders();
        if (SimpMessageHeaderAccessor.getMessageType(headers) != SimpMessageType.MESSAGE) {
            return message;
        }
        String destination = SimpMessageHeaderAccessor.getDestination(headers);
        SessionTopic topic = StompAuthInterceptor.sessionTopic(destination);
        if (topic == null) {
            // 개인 큐(/user/queue/events, /user/queue/draw → /queue/events-*, /queue/draw-*)는 로그인 세션이 살아 있는 연결에만 보낸다.
            if (privateQueue(destination) && socketGuard.subscriber(SimpMessageHeaderAccessor.getSessionId(headers)) == null) {
                return null;
            }
            return message;
        }
        Principal recipient = socketGuard.subscriber(SimpMessageHeaderAccessor.getSessionId(headers));
        if (recipient == null) {
            return null;
        }
        return sessions.participantRole(Long.valueOf(recipient.getName()), topic.sessionId())
                .filter(role -> !topic.teacherOnly() || role == SessionRole.TEACHER)
                .map(role -> message)
                .orElse(null);
    }

    private static boolean privateQueue(String destination) {
        return destination != null && (destination.startsWith("/queue/events-") || destination.startsWith("/queue/draw-"));
    }
}
