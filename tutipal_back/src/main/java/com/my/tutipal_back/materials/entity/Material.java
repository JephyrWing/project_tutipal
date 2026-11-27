package com.my.tutipal_back.materials.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.my.tutipal_back.common.support.BaseTimeEntity;

/** 세션 자료 파일. 목록은 data 없이 MaterialRow 로 읽는다 (MaterialRepository). 청크는 JDBC (pgvector). */
@Entity
@Table(name = "materials")
public class Material extends BaseTimeEntity {

    public enum Status { PROCESSING, DONE, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long sessionId;

    @Column(nullable = false)
    private Long uploaderId;

    @Column(nullable = false, length = 200)
    private String filename;

    @Column(nullable = false)
    private int sizeBytes;

    @Column(nullable = false, length = 500)
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PROCESSING;

    @Column(nullable = false)
    private int chunkCount;

    @Column(length = 200)
    private String error;

    private Instant indexedAt;

    @Column(nullable = false)
    private boolean indexRetryable;

    @Column(nullable = false)
    private int indexRetryCount;

    @Column(nullable = false)
    private boolean originalMissing;

    protected Material() {
    }

    public Material(Long sessionId, Long uploaderId, String filename, String storageKey, int sizeBytes) {
        this.sessionId = sessionId;
        this.uploaderId = uploaderId;
        this.filename = filename;
        this.storageKey = storageKey;
        this.sizeBytes = sizeBytes;
    }

    public Long getId() { return id; }
    public Long getSessionId() { return sessionId; }
    public String getFilename() { return filename; }
    public String getStorageKey() { return storageKey; }
    public Status getStatus() { return status; }
    public int getIndexRetryCount() { return indexRetryCount; }
    public boolean isOriginalMissing() { return originalMissing; }
}
