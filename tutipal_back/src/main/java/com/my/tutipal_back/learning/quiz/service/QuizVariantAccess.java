package com.my.tutipal_back.learning.quiz.service;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.learning.quiz.dto.VariantOriginal;
import com.my.tutipal_back.learning.quiz.entity.Quiz;
import com.my.tutipal_back.learning.quiz.entity.QuizAnswer;
import com.my.tutipal_back.learning.quiz.repository.QuizAnswerRepository;
import com.my.tutipal_back.learning.quiz.repository.QuizRepository;
import com.my.tutipal_back.sessions.entity.SessionRole;
import com.my.tutipal_back.sessions.service.StudySessionService;

/**
 * PT-85 AC-12: 학생용 변형 문항 도구에 넘겨도 되는 문항인지 확인한다.
 * 같은 세션 · 마감된 퀴즈 · 로그인 사용자 본인 답안이 채점됐고 현재 오답(correct == FALSE)일 때만 통과.
 * 본인 답안은 (quizId, 로그인 userId) 로만 조회하므로 다른 학생 답안에는 닿을 수 없다.
 * 교사 재판정은 같은 행을 덮어쓰므로 현재 correct 가 최신 판정이다.
 */
@Service
@Transactional(readOnly = true)
public class QuizVariantAccess {

    private final QuizRepository quizzes;
    private final QuizAnswerRepository answers;
    private final StudySessionService sessions;
    private final Clock clock;

    @Autowired
    public QuizVariantAccess(QuizRepository quizzes, QuizAnswerRepository answers, StudySessionService sessions) {
        this(quizzes, answers, sessions, Clock.systemUTC());
    }

    QuizVariantAccess(QuizRepository quizzes, QuizAnswerRepository answers, StudySessionService sessions,
                      Clock clock) {
        this.quizzes = quizzes;
        this.answers = answers;
        this.sessions = sessions;
        this.clock = clock;
    }

    /**
     * {@link #requireWrongAnswer} 와 같은 조건으로 확인하고, 통과하면 원 문항을 엔티티가 아닌 값으로 돌려준다
     * (tutor 쪽은 JPA 엔티티를 받지 않는다).
     */
    public VariantOriginal requireVariantOriginal(Long userId, Long sessionId, Long quizId) {
        return VariantOriginal.of(requireWrongAnswer(userId, sessionId, quizId));
    }

    /**
     * 통과하면 원 문항을 돌려준다. 거절: sessionId 없음 400, 비참가 404 · 끝난 수업 410(세션 서비스 그대로),
     * 선생님 403, 없는 퀴즈 · 다른 세션 퀴즈 404(존재 숨김), 열린 퀴즈 · 본인 미응답 · 채점 전 · 정답 409.
     */
    Quiz requireWrongAnswer(Long userId, Long sessionId, Long quizId) {
        if (sessionId == null || quizId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "변형할 문항은 수업 안에서만 고를 수 있어요");
        }
        SessionRole role = sessions.requireActiveRole(userId, sessionId);
        if (role != SessionRole.STUDENT) {
            throw new ApiException(HttpStatus.FORBIDDEN, "변형 문항은 학생만 만들 수 있어요");
        }
        Quiz quiz = quizzes.findById(quizId)
                .filter(q -> sessionId.equals(q.getSessionId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "문항을 찾을 수 없어요"));
        if (quiz.isOpen(clock.instant())) {
            throw new ApiException(HttpStatus.CONFLICT, "아직 진행 중인 문항이에요");
        }
        QuizAnswer mine = answers.findByQuizIdAndUserId(quizId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "답하지 않은 문항은 변형할 수 없어요"));
        if (mine.isPending() || !Boolean.FALSE.equals(mine.getCorrect())) {
            throw new ApiException(HttpStatus.CONFLICT, "틀린 문항만 비슷한 문제로 다시 풀 수 있어요");
        }
        return quiz;
    }
}
