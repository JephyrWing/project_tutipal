package com.my.tutipal_back.learning.insights.service;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.my.tutipal_back.learning.insights.repository.ChatQuestionStore;
import com.my.tutipal_back.sessions.service.StudySessionService;

/**
 * PT-87 AC-7: a student who leaves or is removed takes their stored questions of that session with them.
 * Runs synchronously in the leave/kick transaction (same as ScreenShareService.onParticipantRemoved), so the
 * participant row and the questions disappear together.
 */
@Component
public class ParticipantRemovedListener {

    private final ChatQuestionStore store;

    public ParticipantRemovedListener(ChatQuestionStore store) {
        this.store = store;
    }

    @EventListener
    @Transactional
    public void onParticipantRemoved(StudySessionService.ParticipantRemoved removed) {
        store.deleteBySessionAndUser(removed.sessionId(), removed.userId());
    }
}
