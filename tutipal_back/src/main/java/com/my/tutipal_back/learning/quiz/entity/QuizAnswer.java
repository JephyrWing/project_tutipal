package com.my.tutipal_back.learning.quiz.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import com.my.tutipal_back.common.support.BaseTimeEntity;

/** 학생 응답 한 개. gradedBy == null 이면 아직 채점 전 (AI 채점 중 또는 실패 → 선생님이 채점). */
@Entity
@Table(name = "quiz_answers", uniqueConstraints = @UniqueConstraint(columnNames = {"quizId", "userId"}))
public class QuizAnswer extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long quizId;

    @Column(nullable = false)
    private Long userId;

    private Integer choiceIndex;

    @Column(length = 2000)
    private String text;

    private Boolean correct;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private GradedBy gradedBy;

    @Column(length = 500)
    private String feedback;

    private Boolean aiOriginalCorrect;

    @Column(length = 500)
    private String aiOriginalFeedback;

    protected QuizAnswer() {
    }

    public QuizAnswer(Long quizId, Long userId, Integer choiceIndex, String text) {
        this.quizId = quizId;
        this.userId = userId;
        this.choiceIndex = choiceIndex;
        this.text = text;
    }

    public void grade(boolean correct, GradedBy by, String feedback) {
        this.correct = correct;
        this.gradedBy = by;
        this.feedback = feedback;
    }

    public boolean recordAiGrade(boolean verdict, String note) {
        if (aiOriginalCorrect != null) {
            return false;
        }
        aiOriginalCorrect = verdict;
        aiOriginalFeedback = note;
        if (gradedBy == GradedBy.TEACHER) {
            feedback = Boolean.valueOf(verdict).equals(correct) ? note : null;
        } else {
            grade(verdict, GradedBy.AI, note);
        }
        return true;
    }

    public void regradeByTeacher(boolean verdict) {
        Boolean choice = verdict;
        String compatibleFeedback = aiOriginalCorrect != null
                ? (choice.equals(aiOriginalCorrect) ? aiOriginalFeedback : null)
                : (!isPending() && choice.equals(correct) ? feedback : null);
        grade(verdict, GradedBy.TEACHER, compatibleFeedback);
    }

    /** 채점 대기인 답안에만 AI 실패 안내를 표시한다. */
    public boolean gradingFailed(String note) {
        if (gradedBy != null || aiOriginalCorrect != null || note.equals(feedback)) {
            return false;
        }
        this.feedback = note;
        return true;
    }

    public boolean isPending() { return gradedBy == null; }
    public Long getId() { return id; }
    public Long getQuizId() { return quizId; }
    public Long getUserId() { return userId; }
    public Integer getChoiceIndex() { return choiceIndex; }
    public String getText() { return text; }
    public Boolean getCorrect() { return correct; }
    public GradedBy getGradedBy() { return gradedBy; }
    public String getFeedback() { return feedback; }
    public Boolean getAiOriginalCorrect() { return aiOriginalCorrect; }
    public String getAiOriginalFeedback() { return aiOriginalFeedback; }
    public Boolean getTeacherCorrect() { return gradedBy == GradedBy.TEACHER ? correct : null; }
}
