package com.my.tutipal_back.learning.quiz.entity;

import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.my.tutipal_back.learning.quiz.dto.QuizDtos.QuizDraft;

@Entity
@Table(name = "quiz_draft_approvals")
public class QuizDraftApproval {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long sessionId;

    @Column(nullable = false)
    private Long teacherId;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private QuizType approvedType;

    @Column(length = 1000)
    private String approvedQuestion;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private List<String> approvedChoices;

    private Integer approvedAnswerIndex;

    @Column(length = 2000)
    private String approvedModelAnswer;

    private Long sentQuizId;

    protected QuizDraftApproval() {
    }

    public QuizDraftApproval(long sessionId, long teacherId) {
        this.sessionId = sessionId;
        this.teacherId = teacherId;
    }

    public void approve(QuizDraft content) {
        approvedType = content.type();
        approvedQuestion = content.question();
        approvedChoices = content.choices();
        approvedAnswerIndex = content.answerIndex();
        approvedModelAnswer = content.modelAnswer();
    }

    public QuizDraft approvedContent() {
        return approvedType == null ? null : new QuizDraft(approvedType, approvedQuestion, approvedChoices,
                approvedAnswerIndex, approvedModelAnswer);
    }

    public void markSent(long quizId) { this.sentQuizId = quizId; }
    public Long getId() { return id; }
    public Long getSentQuizId() { return sentQuizId; }
}
