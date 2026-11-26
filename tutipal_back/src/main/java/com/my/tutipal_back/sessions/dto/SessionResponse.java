package com.my.tutipal_back.sessions.dto;

import com.my.tutipal_back.sessions.entity.SessionRole;
import com.my.tutipal_back.sessions.entity.StudySession;

/** 세션 정보 + 내 역할. topic 은 과목 이름. */
public record SessionResponse(
        Long id,
        String title,
        String topic,
        String inviteCode,
        SessionRole myRole,
        int difficulty,
        boolean hideAnswers,
        boolean preferMaterials,
        long participantCount,
        boolean ended,
        /** 내가 즐겨찾기한 방 (홈 목록에서 맨 위) */
        boolean pinned,
        /** 수업 AI 대화 보관 일수 (수업 종료 뒤 0..90일). 학생도 자기 대화가 언제 지워지는지 알 수 있게 모두에게 보낸다 */
        Integer aiRetentionDays) {

    public static SessionResponse of(StudySession s, SessionRole myRole, long participantCount) {
        return new SessionResponse(s.getId(), s.getTitle(), s.getTopic(),
                s.getInviteCode(), myRole, s.getDifficulty(), s.isHideAnswers(), s.isPreferMaterials(),
                participantCount, s.isEnded(), false, s.getAiRetentionDays());
    }

    public SessionResponse withPinned(boolean pinned) {
        return new SessionResponse(id, title, topic, inviteCode, myRole, difficulty, hideAnswers, preferMaterials,
                participantCount, ended, pinned, aiRetentionDays);
    }
}
