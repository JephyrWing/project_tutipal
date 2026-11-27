package com.my.tutipal_back.learning.quiz.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.learning.quiz.dto.QuizDtos.QuizDraft;
import com.my.tutipal_back.learning.quiz.entity.QuizDraftApproval;
import com.my.tutipal_back.learning.quiz.repository.QuizDraftApprovalRepository;
import com.my.tutipal_back.sessions.service.SessionEnded;
import com.my.tutipal_back.sessions.service.StudySessionService;

@Service
public class QuizDrafts {
    private final QuizDraftApprovalRepository drafts;
    private final StudySessionService sessions;

    public QuizDrafts(QuizDraftApprovalRepository drafts, StudySessionService sessions) {
        this.drafts = drafts;
        this.sessions = sessions;
    }

    @Transactional
    public QuizDraft register(long teacherId, long sessionId, QuizDraft generated) {
        sessions.requireActiveTeacherForQuizWrite(teacherId, sessionId);
        long id = drafts.save(new QuizDraftApproval(sessionId, teacherId)).getId();
        return new QuizDraft(generated.type(), generated.question(), generated.choices(), generated.answerIndex(),
                generated.modelAnswer(), id);
    }

    QuizDraftApproval requireOwned(long teacherId, long sessionId, long id) {
        return drafts.findByIdAndSessionIdAndTeacherId(id, sessionId, teacherId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "문제 초안을 찾을 수 없어요"));
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void discardUnsent(SessionEnded event) {
        drafts.deleteUnsent(event.sessionId());
    }
}
