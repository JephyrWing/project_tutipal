package com.my.tutipal_back.materials.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import com.my.tutipal_back.materials.entity.Material;
import com.my.tutipal_back.materials.repository.MaterialRepository;
import com.my.tutipal_back.materials.repository.MaterialRepository.MaterialRow;
import com.my.tutipal_back.materials.repository.MaterialRepository.ReusableMaterial;
import com.my.tutipal_back.sessions.service.StudySessionService;
import com.my.tutipal_back.common.support.AfterCommit;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.common.realtime.SessionEvents;

/**
 * 세션 자료 원본과 접근 권한을 관리한다. 작업자는 커밋된 PROCESSING 자료를 읽어 색인한다.
 * 학생 · 선생님 모두 목록 보기 · 내려받기.
 */
@Service
@Transactional(readOnly = true)
public class MaterialService {

    static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final Set<String> EXTENSIONS = Set.of("txt", "md", "pdf");
    private final MaterialRepository materials;
    private final StudySessionService sessions;
    private final SessionEvents events;
    private final MaterialObjectStore objects;
    private final MaterialReconciliation reconciliation;

    public MaterialService(MaterialRepository materials, StudySessionService sessions,
                           SessionEvents events, MaterialObjectStore objects, MaterialReconciliation reconciliation) {
        this.materials = materials;
        this.sessions = sessions;
        this.events = events;
        this.objects = objects;
        this.reconciliation = reconciliation;
    }

    @Transactional
    public MaterialRow upload(Long userId, Long sessionId, MultipartFile file, boolean alert) {
        sessions.requireTeacherOf(userId, sessionId);
        sessions.requireActiveRole(userId, sessionId);
        String name = safeName(file.getOriginalFilename());
        if (!EXTENSIONS.contains(extension(name))) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "txt · md · pdf 파일만 올릴 수 있어요");
        }
        if (file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "10MB 이하 파일만 올릴 수 있어요");
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (java.io.IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "파일을 읽지 못했어요");
        }
        return store(userId, sessionId, name, data, alert);
    }

    public List<ReusableMaterial> reusable(Long userId, Long sessionId) {
        sessions.requireTeacherOf(userId, sessionId);
        sessions.requireActiveRole(userId, sessionId);
        return materials.reusable(userId, sessionId);
    }

    @Transactional
    public MaterialRow reuse(Long userId, Long sessionId, Long sourceId, boolean alert) {
        sessions.requireTeacherOf(userId, sessionId);
        sessions.requireActiveRole(userId, sessionId);
        Material source = find(sourceId);
        // 이전 수업은 종료됐어도 그 수업의 교사가 다시 사용할 수 있다.
        sessions.requireTeacherOf(userId, source.getSessionId());
        if (sessionId.equals(source.getSessionId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "다른 수업의 자료를 선택해 주세요");
        }
        requireOriginal(source);
        // 대상 수업의 원본·청크를 따로 저장해 어느 쪽을 삭제해도 다른 수업에 영향이 없다.
        return store(userId, sessionId, source.getFilename(), objects.get(source.getStorageKey()), alert);
    }

    private MaterialRow store(Long userId, Long sessionId, String name, byte[] data, boolean alert) {
        String key = objects.newKey();
        reconciliation.reserveUpload(sessionId, key);
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        reconciliation.cleanupAfterTransaction(key);
                    }
                }
            });
        objects.put(key, data);
        Material m = materials.save(new Material(sessionId, userId, name, key, data.length));
        reconciliation.finishUpload(key);
        Long id = m.getId();
        events.toSession(sessionId, alert ? "materialAlert" : "materials", id);
        return materials.row(id).orElseThrow();
    }

    public List<MaterialRow> list(Long userId, Long sessionId) {
        sessions.requireActiveRole(userId, sessionId);
        return materials.rows(sessionId);
    }

    /** 내려받기: 그 세션 참가자만 */
    public Material download(Long userId, Long materialId) {
        Material m = find(materialId);
        sessions.requireActiveRole(userId, m.getSessionId());
        requireOriginal(m);
        return m;
    }

    public byte[] downloadData(Material material) { return objects.get(material.getStorageKey()); }

    @Transactional
    public void delete(Long userId, Long materialId) {
        MaterialRow m = materials.row(materialId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "자료를 찾을 수 없어요"));
        sessions.requireTeacherOf(userId, m.sessionId());
        String key = find(materialId).getStorageKey();
        materials.deleteById(materialId);   // 청크는 ON DELETE CASCADE
        reconciliation.recordDeletion(m.sessionId(), key);
        AfterCommit.run(() -> reconciliation.cleanupAfterTransaction(key));
        events.toSession(m.sessionId(), "materials", materialId);
    }

    @Transactional
    public MaterialRow requestIndex(Long userId, Long materialId) {
        Material material = find(materialId);
        Long sessionId = material.getSessionId();
        sessions.requireTeacherOf(userId, sessionId);
        sessions.requireActiveRole(userId, sessionId);
        requireOriginal(material);
        if (materials.queueIndex(materialId) == 1) {
            events.toSession(sessionId, "materials", materialId);
        }
        MaterialRow row = materials.row(materialId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "자료를 찾을 수 없어요"));
        if (row.status() == Material.Status.FAILED && !row.indexRetryable()) {
            throw new ApiException(HttpStatus.CONFLICT, "파일을 확인한 뒤 새로 올려 주세요");
        }
        return row;
    }

    /** 경로 · 제어 문자를 뺀 파일 이름 (내려받기 헤더 · 화면 표시용) */
    static String safeName(String original) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"]", "").strip();
        if (name.isEmpty()) {
            name = "자료";
        }
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private Material find(Long id) {
        return materials.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "자료를 찾을 수 없어요"));
    }

    private void requireOriginal(Material material) {
        if (material.isOriginalMissing()) {
            throw new ApiException(HttpStatus.CONFLICT, "원본 파일을 사용할 수 없어요. 운영자에게 문의해 주세요");
        }
    }
}
