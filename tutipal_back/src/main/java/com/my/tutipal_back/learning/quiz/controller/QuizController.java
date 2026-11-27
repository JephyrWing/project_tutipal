package com.my.tutipal_back.learning.quiz.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.my.tutipal_back.learning.quiz.dto.QuizDtos.AnswerRequest;
import com.my.tutipal_back.learning.quiz.dto.QuizDtos.AnswerRow;
import com.my.tutipal_back.learning.quiz.dto.QuizDtos.CreateQuizRequest;
import com.my.tutipal_back.learning.quiz.dto.QuizDtos.GenerateQuizRequest;
import com.my.tutipal_back.learning.quiz.dto.QuizDtos.GradeRequest;
import com.my.tutipal_back.learning.quiz.dto.QuizDtos.MyAnswer;
import com.my.tutipal_back.learning.quiz.dto.QuizDtos.QuizDraft;
import com.my.tutipal_back.learning.quiz.dto.QuizDtos.QuizSummary;
import com.my.tutipal_back.learning.quiz.dto.QuizDtos.StudentQuiz;
import com.my.tutipal_back.learning.quiz.service.QuizService;

@RestController
@RequestMapping("/api")
public class QuizController {

    private final QuizService service;

    public QuizController(QuizService service) {
        this.service = service;
    }

    @PostMapping("/sessions/{sessionId}/quizzes")
    @ResponseStatus(HttpStatus.CREATED)
    public QuizSummary create(@AuthenticationPrincipal OAuth2User me, @PathVariable Long sessionId,
                              @Valid @RequestBody CreateQuizRequest request) {
        return service.create(userId(me), sessionId, request);
    }

    @PostMapping("/sessions/{sessionId}/quizzes/generate")
    public QuizDraft generate(@AuthenticationPrincipal OAuth2User me, @PathVariable Long sessionId,
                              @Valid @RequestBody GenerateQuizRequest request) {
        return service.generate(userId(me), sessionId, request);
    }

    @PostMapping("/sessions/{sessionId}/quizzes/drafts/{draftId}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approveDraft(@AuthenticationPrincipal OAuth2User me, @PathVariable Long sessionId,
                             @PathVariable Long draftId, @Valid @RequestBody CreateQuizRequest request) {
        service.approveDraft(userId(me), sessionId, draftId, request);
    }

    @PostMapping("/sessions/{sessionId}/quizzes/drafts/{draftId}/send")
    @ResponseStatus(HttpStatus.CREATED)
    public QuizSummary sendDraft(@AuthenticationPrincipal OAuth2User me, @PathVariable Long sessionId,
                                 @PathVariable Long draftId, @Valid @RequestBody CreateQuizRequest request) {
        return service.sendDraft(userId(me), sessionId, draftId, request);
    }

    @GetMapping("/sessions/{sessionId}/quizzes")
    public List<QuizSummary> list(@AuthenticationPrincipal OAuth2User me, @PathVariable Long sessionId) {
        return service.list(userId(me), sessionId);
    }

    @GetMapping("/sessions/{sessionId}/quizzes/open")
    public List<StudentQuiz> open(@AuthenticationPrincipal OAuth2User me, @PathVariable Long sessionId) {
        return service.openForStudent(userId(me), sessionId);
    }

    @GetMapping("/quizzes/{quizId}")
    public StudentQuiz get(@AuthenticationPrincipal OAuth2User me, @PathVariable Long quizId) {
        return service.getForStudent(userId(me), quizId);
    }

    @PostMapping("/quizzes/{quizId}/answers")
    public MyAnswer answer(@AuthenticationPrincipal OAuth2User me, @PathVariable Long quizId,
                           @Valid @RequestBody AnswerRequest request) {
        return service.answer(userId(me), quizId, request);
    }

    @PostMapping("/quizzes/{quizId}/close")
    public QuizSummary close(@AuthenticationPrincipal OAuth2User me, @PathVariable Long quizId) {
        return service.close(userId(me), quizId);
    }

    @GetMapping("/quizzes/{quizId}/answers")
    public List<AnswerRow> answers(@AuthenticationPrincipal OAuth2User me, @PathVariable Long quizId) {
        return service.answers(userId(me), quizId);
    }

    @PutMapping("/quiz-answers/{answerId}")
    public AnswerRow regrade(@AuthenticationPrincipal OAuth2User me, @PathVariable Long answerId,
                             @Valid @RequestBody GradeRequest request) {
        return service.regrade(userId(me), answerId, request.correct());
    }

    private static Long userId(OAuth2User me) {
        return Long.valueOf(me.getName());
    }
}
