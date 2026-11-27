package com.my.tutipal_back.sessions.dto;

import java.time.Instant;

import com.my.tutipal_back.sessions.entity.StudySession;

public record EndedSessionResponse(Long id, String title, String topic, Instant endedAt) {

    public static EndedSessionResponse of(StudySession session) {
        return new EndedSessionResponse(session.getId(), session.getTitle(), session.getTopic(), session.getEndedAt());
    }
}
