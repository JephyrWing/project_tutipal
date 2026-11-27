package com.my.tutipal_back.learning.results.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.my.tutipal_back.learning.results.dto.TeacherLearningResults;
import com.my.tutipal_back.learning.results.repository.TeacherLearningResultsStore;
import com.my.tutipal_back.sessions.service.StudySessionService;

@Service
public class TeacherLearningResultsService {
    private final StudySessionService sessions;
    private final TeacherLearningResultsStore store;

    public TeacherLearningResultsService(StudySessionService sessions, TeacherLearningResultsStore store) {
        this.sessions = sessions;
        this.store = store;
    }

    @Transactional(readOnly = true)
    public TeacherLearningResults results(long teacherId, long sessionId) {
        sessions.requireTeacherOf(teacherId, sessionId);
        return store.results(sessionId);
    }
}
