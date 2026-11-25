package com.my.tutipal_back.materials.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.ai.client.AiSlots;
import com.my.tutipal_back.ai.client.AiUnavailableException;
import com.my.tutipal_back.common.realtime.SessionEvents;
import com.my.tutipal_back.materials.entity.Material;
import com.my.tutipal_back.materials.repository.MaterialRepository;

@Component
class MaterialIndexWorker {

    private static final Logger log = LoggerFactory.getLogger(MaterialIndexWorker.class);
    private static final int CHUNK = 800;
    private static final int OVERLAP = 100;
    private static final int BATCH = 32;
    private static final List<Integer> RETRY_DELAYS_SECONDS = List.of(10, 30);

    private final MaterialRepository materials;
    private final MaterialObjectStore objects;
    private final MaterialTextExtractor extractor;
    private final AiClient ai;
    private final AiSlots slots;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final SessionEvents events;

    MaterialIndexWorker(MaterialRepository materials, MaterialObjectStore objects, MaterialTextExtractor extractor,
                        AiClient ai, AiSlots slots, JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                        SessionEvents events) {
        this.materials = materials;
        this.objects = objects;
        this.extractor = extractor;
        this.ai = ai;
        this.slots = slots;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.events = events;
    }

    @Scheduled(fixedDelay = 1000)
    void indexPending() {
        List<Long> pending = jdbc.queryForList("""
                SELECT id FROM materials WHERE status = 'PROCESSING'
                    AND (index_retry_at IS NULL OR index_retry_at <= statement_timestamp())
                ORDER BY updated_at, id LIMIT 32
                """, Long.class);
        for (Long id : pending) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            try {
                Long sessionId = transaction.execute(status -> index(id));
                if (sessionId != null) {
                    events.toSession(sessionId, "materials", id);
                }
            } catch (RuntimeException e) {
                // DB 쓰기가 실패하면 롤백 후 같은 PROCESSING 작업을 다음 순회에 읽는다.
                log.warn("material index transaction failed: id={} {}", id, e.getClass().getSimpleName());
            }
        }
    }

    private Long index(Long id) {
        // 음수 자료 ID의 트랜잭션 잠금. DB 연결이 끊기면 풀리며, 계산 중에도 자료 행은 삭제할 수 있다.
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, -id))) {
            return null;
        }
        // 다른 작업자가 그 사이 같은 ID를 완료하거나 다음 재시도를 예약했을 수 있다.
        Material material = materials.dueIndex(id).orElse(null);
        if (material == null) {
            return null;
        }

        byte[] data;
        try {
            data = objects.get(material.getStorageKey());
        } catch (MaterialObjectStore.ReadFailure e) {
            return switch (e.kind()) {
                case TEMPORARY -> retryOrFail(material, "자료 저장소 연결에 실패했어요. 잠시 후 다시 시도해 주세요");
                case MISSING -> fail(material, "원본 파일을 찾지 못했어요. 파일을 다시 올려 주세요", false);
                case FORBIDDEN -> fail(material, "원본 파일 접근 권한을 확인한 뒤 다시 시도해 주세요", true);
                case UNKNOWN -> fail(material, "원본 파일을 읽지 못했어요. 잠시 후 다시 시도해 주세요", true);
            };
        }

        List<Chunk> chunks;
        try {
            chunks = extractor.extract(material.getFilename(), data).stream()
                    .flatMap(page -> Chunker.split(page.text(), CHUNK, OVERLAP).stream()
                            .map(content -> new Chunk(page.page(), content)))
                    .toList();
        } catch (MaterialTextExtractor.InvalidPdf e) {
            return fail(material, e.getMessage(), false);
        }
        if (chunks.isEmpty()) {
            return fail(material, "글자를 찾지 못했어요. 텍스트가 있는 파일을 올려 주세요", false);
        }

        AiClient.EmbedResponse embedding;
        try {
            embedding = embed(chunks);
        } catch (RuntimeException e) {
            log.warn("material embedding failed: id={} {}", id, e.getClass().getSimpleName());
            String error = "임베딩을 만들지 못했어요. 잠시 후 다시 시도해 주세요";
            return e instanceof AiUnavailableException unavailable && unavailable.isTemporaryEmbeddingFailure()
                    ? retryOrFail(material, error) : fail(material, error, true);
        }

        // 삭제가 먼저 커밋되면 수정할 행이 없다. 갱신이 먼저라면 FK가 이후 삭제에서 조각을 정리한다.
        int updated = jdbc.update("""
                UPDATE materials
                SET status = 'DONE', indexed_at = statement_timestamp(), chunk_count = ?, error = NULL,
                    index_retryable = FALSE, index_retry_count = 0, index_retry_at = NULL,
                    updated_at = statement_timestamp()
                WHERE id = ? AND status = 'PROCESSING'
                """, chunks.size(), id);
        if (updated == 0) {
            return null;
        }
        jdbc.update("DELETE FROM material_chunks WHERE material_id = ?", id);
        for (int from = 0; from < chunks.size(); from += BATCH) {
            List<Object[]> rows = new ArrayList<>(BATCH);
            for (int i = from; i < Math.min(chunks.size(), from + BATCH); i++) {
                Chunk chunk = chunks.get(i);
                rows.add(new Object[] {id, material.getSessionId(), chunk.page(), chunk.content(),
                        AiClient.pgVector(embedding.vectors().get(i)), embedding.model()});
            }
            jdbc.batchUpdate("""
                    INSERT INTO material_chunks (material_id, session_id, page, content, embedding, embedding_model)
                    VALUES (?, ?, ?, ?, ?::vector, ?)
                    """, rows);
        }
        return material.getSessionId();
    }

    private AiClient.EmbedResponse embed(List<Chunk> chunks) {
        String model = null;
        List<float[]> vectors = new ArrayList<>(chunks.size());
        for (int from = 0; from < chunks.size(); from += BATCH) {
            List<String> batch = chunks.subList(from, Math.min(chunks.size(), from + BATCH)).stream()
                    .map(Chunk::content)
                    .toList();
            AiClient.EmbedResponse response = slots.run(Duration.ofMinutes(5), () -> ai.embedWithModel(batch));
            if (model != null && !model.equals(response.model())) {
                throw new IllegalStateException("Embedding model changed during indexing");
            }
            model = response.model();
            vectors.addAll(response.vectors());
        }
        return new AiClient.EmbedResponse(model, vectors);
    }

    private Long retryOrFail(Material material, String error) {
        if (Thread.currentThread().isInterrupted()) {
            return null;
        }
        int retries = material.getIndexRetryCount();
        if (retries == RETRY_DELAYS_SECONDS.size()) {
            return fail(material, error, true);
        }
        jdbc.update("""
                UPDATE materials
                SET index_retry_count = index_retry_count + 1,
                    index_retry_at = statement_timestamp() + make_interval(secs => ?),
                    updated_at = statement_timestamp()
                WHERE id = ? AND status = 'PROCESSING'
                """, RETRY_DELAYS_SECONDS.get(retries), material.getId());
        return null;
    }

    private Long fail(Material material, String error, boolean retryable) {
        if (Thread.currentThread().isInterrupted()) {
            return null; // 종료가 진행되면 다음 프로세스가 같은 자료를 이어받는다.
        }
        int updated = jdbc.update("""
                UPDATE materials
                SET status = 'FAILED', error = ?, index_retryable = ?, index_retry_at = NULL,
                    updated_at = statement_timestamp()
                WHERE id = ? AND status = 'PROCESSING'
                """, error, retryable, material.getId());
        return updated == 1 ? material.getSessionId() : null;
    }

    private record Chunk(Integer page, String content) { }
}
