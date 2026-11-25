package com.my.tutipal_back.learning.tutor.service;

import java.util.concurrent.atomic.AtomicBoolean;

import com.my.tutipal_back.ai.quota.AiQuotaTicket;
import com.my.tutipal_back.sessions.entity.SessionRole;

/**
 * {@link TutorService#openChat} 결과: CHAT 한도 예약, openChat 에서 확인한 세션 역할(개인 학습이면 null),
 * 그리고 Agent 턴이면 사용자당 동시 턴 자리. {@link TutorService#chat} 에 그대로 넘기면 chat 이 끝날 때
 * (정상 · 오류 · 중지 모두) {@link #release()} 한다. chat 에 넘기기 전에 실패하면 호출자가 release 한다.
 */
public final class OpenedChat {

    private static final Runnable NOTHING = () -> { };

    private final AiQuotaTicket ticket;
    private final SessionRole role;
    private final Runnable onRelease;
    private final AtomicBoolean released = new AtomicBoolean(false);

    OpenedChat(AiQuotaTicket ticket, SessionRole role, Runnable onRelease) {
        this.ticket = ticket;
        this.role = role;
        this.onRelease = onRelease == null ? NOTHING : onRelease;
    }

    AiQuotaTicket ticket() {
        return ticket;
    }

    SessionRole role() {
        return role;
    }

    /**
     * 모델을 아직 안 불렀으면 한도를 환불하고(AiQuotaTicket.releaseIfUnused) 동시 턴 자리를 돌려준다.
     * 여러 번 불러도 한 번만 처리한다.
     */
    public void release() {
        if (!released.compareAndSet(false, true)) {
            return;
        }
        try {
            ticket.releaseIfUnused();
        } finally {
            onRelease.run();
        }
    }
}
