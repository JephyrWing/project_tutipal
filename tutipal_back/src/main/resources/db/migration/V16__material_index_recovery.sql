-- 작업의 최근 상태와 마지막으로 성공한 검색 공개 시점을 별도로 유지한다.
ALTER TABLE materials ADD COLUMN indexed_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE materials ADD COLUMN index_retryable BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE materials SET indexed_at = updated_at WHERE status = 'DONE' AND chunk_count > 0;
UPDATE materials SET index_retryable = TRUE
WHERE status = 'FAILED'
  AND error IS DISTINCT FROM '글자를 찾지 못했어요 (스캔한 PDF 는 아직 못 읽어요)';

-- 이전 작업자가 커밋한 미완료 조각은 재기동 시 원본에서 다시 만든다.
DELETE FROM material_chunks c USING materials m
WHERE c.material_id = m.id AND m.status IN ('PROCESSING', 'FAILED');

CREATE INDEX idx_materials_pending_index ON materials (updated_at, id) WHERE status = 'PROCESSING';
