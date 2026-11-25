-- V17은 PT-96의 도움 채팅에 예약되어 있다.
ALTER TABLE materials ADD COLUMN index_retry_count INTEGER NOT NULL DEFAULT 0
    CHECK (index_retry_count BETWEEN 0 AND 2);
ALTER TABLE materials ADD COLUMN index_retry_at TIMESTAMP(6) WITH TIME ZONE;
