package com.my.tutipal_back.sessions.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.my.tutipal_back.common.support.BaseTimeEntity;

/** 단체 학습 세션. 선생님 챗봇 설정(주제 · 난이도 · 정답 숨김)이 학생 챗봇에 그대로 적용된다. */
@Entity
@Table(name = "study_sessions")
public class StudySession extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String title;

    /** 과목 이름 ("코딩", "한국사", 직접 쓴 이름 등) */
    @Column(nullable = false, length = 30)
    private String topic;

    @Column(nullable = false, length = 6, unique = true)
    private String inviteCode;

    @Column(nullable = false)
    private Long ownerId;

    @Column(nullable = false)
    @JdbcTypeCode(SqlTypes.SMALLINT)
    private int difficulty = 3;         // 1 쉬움 ~ 5 어려움

    @Column(nullable = false)
    private boolean hideAnswers;        // 정답을 바로 알려주지 않기

    @Column(nullable = false)
    private boolean preferMaterials;    // 자료 근거 우선 (ponytail: 저장만, RAG 는 5단계)

    private Instant endedAt;

    /**
     * PT-13 결정 A: 수업 AI 대화 보관 일수 (수업 종료 뒤 0..90일, 0 = 종료 직후 삭제). 만들 때 선생님이 반드시 정한다.
     * V24 이전 수업은 90 으로 채웠다. null 은 API 밖에서 넣은 행뿐이며 보관 작업이 90 으로 본다.
     */
    @JdbcTypeCode(SqlTypes.SMALLINT)
    private Integer aiRetentionDays;

    public static final int MAX_AI_RETENTION_DAYS = 90;

    protected StudySession() {
    }

    public StudySession(String title, String topic, String inviteCode, Long ownerId, int aiRetentionDays) {
        this.title = title;
        this.topic = topic;
        this.inviteCode = inviteCode;
        this.ownerId = ownerId;
        changeAiRetentionDays(aiRetentionDays);
    }

    /** 0..90 (요청 DTO 가 먼저 막고, 여기서도 한 번 더 확인한다). 끝난 수업에서도 바꿀 수 있다. */
    public void changeAiRetentionDays(int days) {
        if (days < 0 || days > MAX_AI_RETENTION_DAYS) {
            throw new IllegalArgumentException("aiRetentionDays must be 0.." + MAX_AI_RETENTION_DAYS);
        }
        this.aiRetentionDays = days;
    }

    public void updateSettings(String topic, int difficulty, boolean hideAnswers, boolean preferMaterials) {
        this.topic = topic;
        this.difficulty = difficulty;
        this.hideAnswers = hideAnswers;
        this.preferMaterials = preferMaterials;
    }

    public void end(Instant now) {
        if (endedAt == null) {
            endedAt = now;
        }
    }

    public boolean isEnded() { return endedAt != null; }
    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getTopic() { return topic; }
    public String getInviteCode() { return inviteCode; }
    public Long getOwnerId() { return ownerId; }
    public int getDifficulty() { return difficulty; }
    public boolean isHideAnswers() { return hideAnswers; }
    public boolean isPreferMaterials() { return preferMaterials; }
    public Instant getEndedAt() { return endedAt; }
    public Integer getAiRetentionDays() { return aiRetentionDays; }
}
