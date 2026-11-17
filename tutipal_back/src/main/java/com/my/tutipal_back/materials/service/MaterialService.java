package com.my.tutipal_back.materials.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.my.tutipal_back.materials.entity.Material;
import com.my.tutipal_back.materials.repository.MaterialRepository;
import com.my.tutipal_back.materials.repository.MaterialRepository.MaterialRow;
import com.my.tutipal_back.sessions.service.StudySessionService;
import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.ai.client.AiSlots;
import com.my.tutipal_back.common.support.AfterCommit;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.common.realtime.SessionEvents;

/**
 * 세션 자료 (목업 28) + RAG 검색. 선생님이 올리면 커밋 뒤 가상 스레드에서 글자 뽑기 → 조각 → 임베딩 → 청크 저장.
 * 학생 · 선생님 모두 목록 보기 · 내려받기. 챗봇은 search() 로 관련 조각만 받는다.
 */
@Service
@Transactional(readOnly = true)
public class MaterialService {

    private static final Logger log = LoggerFactory.getLogger(MaterialService.class);
    static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final Set<String> EXTENSIONS = Set.of("txt", "md", "pdf");
    private static final int CHUNK = 800;
    private static final int OVERLAP = 100;
    private static final int BATCH = 32;
    /** 이보다 덜 비슷하면 "관련 없음" 으로 보고 챗봇에 안 넘김. ponytail: bge-m3 로 몇 번 본 값, 자료가 쌓이면 다시 맞추기 */
    static final double MIN_SIMILARITY = 0.5;

    public record Hit(String filename, Integer page, String content, double similarity) {
        public String source() {
            return page == null ? filename : filename + " p." + page;
        }
    }

    private final MaterialRepository materials;
    private final StudySessionService sessions;
    private final AiClient ai;
    private final AiSlots slots;
    private final JdbcTemplate jdbc;
    private final SessionEvents events;
    private final MaterialObjectStore objects;
    private final MaterialTextExtractor extractor;

    public MaterialService(MaterialRepository materials, StudySessionService sessions, AiClient ai, AiSlots slots,
                           JdbcTemplate jdbc, SessionEvents events, MaterialObjectStore objects, MaterialTextExtractor extractor) {
        this.materials = materials;
        this.sessions = sessions;
        this.ai = ai;
        this.slots = slots;
        this.jdbc = jdbc;
        this.events = events;
        this.objects = objects;
        this.extractor = extractor;
    }

    @Transactional
    public MaterialRow upload(Long userId, Long sessionId, MultipartFile file) {
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
        String key = objects.put(data);
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) objects.delete(key);
                }
            });
        Material m = materials.save(new Material(sessionId, userId, name, key, data.length));
        Long id = m.getId();
        AfterCommit.run(() -> Thread.ofVirtual().name("material-index").start(() -> index(id)));
        events.toSession(sessionId, "materials", id);
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
        AfterCommit.run(() -> objects.delete(key));
        events.toSession(m.sessionId(), "materials", materialId);
    }

    /**
     * 챗봇용: 질문과 관련 있는 자료 조각 (최대 k개, MIN_SIMILARITY 이상). 자료가 없거나 AI 가 안 되면 빈 목록 (챗봇은 그냥 답함).
     * 세션 참가자 확인은 부르는 쪽(TutorService → chatSettings)에서 이미 함.
     */
    public List<Hit> search(Long sessionId, String query, int k) {
        Boolean any = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM material_chunks WHERE session_id = ?)", Boolean.class, sessionId);
        if (!Boolean.TRUE.equals(any) || query == null || query.isBlank()) {
            return List.of();
        }
        try {
            AiClient.EmbedResponse embedded = ai.embedWithModel(List.of(query.strip()));
            String vec = AiClient.pgVector(embedded.vectors().getFirst());
            return jdbc.query("""
                    SELECT m.filename, c.page, c.content, 1 - (c.embedding <=> ?::vector) AS sim
                    FROM material_chunks c JOIN materials m ON m.id = c.material_id
                    WHERE c.session_id = ? AND c.embedding_model = ?
                    ORDER BY c.embedding <=> ?::vector LIMIT ?""",
                    (rs, i) -> new Hit(rs.getString(1), (Integer) rs.getObject(2), rs.getString(3), rs.getDouble(4)),
                    vec, sessionId, embedded.model(), vec, k)
                    .stream().filter(h -> h.similarity() >= MIN_SIMILARITY).toList();
        } catch (RuntimeException e) {
            log.warn("material search failed: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    /** 커밋 뒤 가상 스레드: 글자 뽑기 → 조각 → 임베딩 → 저장. 실패하면 FAILED + 이유 */
    void index(Long materialId) {
        Material m = materials.findById(materialId).orElse(null);
        if (m == null) {
            return;
        }
        try {
            List<AiClient.Page> pages = extractor.extract(m.getFilename(), objects.get(m.getStorageKey()));
            List<Integer> chunkPages = new ArrayList<>();
            List<String> chunks = new ArrayList<>();
            for (AiClient.Page p : pages) {
                for (String c : Chunker.split(p.text(), CHUNK, OVERLAP)) {
                    chunkPages.add(p.page());
                    chunks.add(c);
                }
            }
            if (chunks.isEmpty()) {
                materials.finish(materialId, Material.Status.FAILED, 0, "글자를 찾지 못했어요 (스캔한 PDF 는 아직 못 읽어요)");
                return;
            }
            for (int from = 0; from < chunks.size(); from += BATCH) {
                List<String> batch = chunks.subList(from, Math.min(chunks.size(), from + BATCH));
                AiClient.EmbedResponse embedded = slots.run(Duration.ofMinutes(5), () -> ai.embedWithModel(batch));
                List<float[]> vectors = embedded.vectors();
                List<Object[]> rows = new ArrayList<>();
                for (int i = 0; i < batch.size(); i++) {
                    rows.add(new Object[] {materialId, m.getSessionId(), chunkPages.get(from + i), batch.get(i), AiClient.pgVector(vectors.get(i)), embedded.model()});
                }
                jdbc.batchUpdate("INSERT INTO material_chunks (material_id, session_id, page, content, embedding, embedding_model) VALUES (?, ?, ?, ?, ?::vector, ?)", rows);
            }
            materials.finish(materialId, Material.Status.DONE, chunks.size(), null);
        } catch (RuntimeException e) {
            log.warn("material index failed: id={} {}", materialId, e.getClass().getSimpleName());
            jdbc.update("DELETE FROM material_chunks WHERE material_id = ?", materialId);
            materials.finish(materialId, Material.Status.FAILED, 0, "처리하지 못했어요. 다시 올려 주세요");
        }
        events.toSession(m.getSessionId(), "materials", materialId);
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
}
