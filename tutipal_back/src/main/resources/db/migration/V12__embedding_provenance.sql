-- 차원이 같아도 서로 다른 공급자/모델의 벡터는 비교하지 않는다.
ALTER TABLE material_chunks ADD COLUMN embedding_model VARCHAR(200) NOT NULL DEFAULT 'legacy:unknown';
ALTER TABLE review_items ADD COLUMN embedding_model VARCHAR(200) NOT NULL DEFAULT 'legacy:unknown';
CREATE INDEX idx_chunks_embedding_model ON material_chunks (session_id, embedding_model);
CREATE INDEX idx_review_embedding_model ON review_items (user_id, embedding_model);
