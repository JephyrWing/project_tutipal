-- In-flight observations from before a deletion must not recreate deleted records.
-- Existing users and review_items are preserved.
ALTER TABLE users ADD COLUMN review_generation BIGINT NOT NULL DEFAULT 0;
