-- PT-86: 조각을 만든 청커(char800-v1 | section-v1)를 기록한다. 기존 조각은 모두 기존 글자 수 분할이다.
-- 검색 필터가 아니다 (출처 격리는 계속 embedding_model). 재인덱싱 절차는 docs/RAG_REINDEX.md.
-- 참고: V17 번호는 비어 있다 (PT-96 도움 채팅 초안이 V19 로 옮겨 감, docs/HELP_CHAT.md). 이미 V18 이후가 적용된 DB 가
-- 있으므로 V17 을 새로 만들지 않는다 (Flyway 기본 outOfOrder=false 에서 실패함).
ALTER TABLE material_chunks ADD COLUMN chunker VARCHAR(40) NOT NULL DEFAULT 'char800-v1';
