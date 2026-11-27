ALTER TABLE materials ADD COLUMN original_missing BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_materials_storage_key ON materials (storage_key);

CREATE TABLE material_object_cleanup (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    storage_scope TEXT NOT NULL,
    storage_key   VARCHAR(500) NOT NULL,
    session_id    BIGINT NOT NULL,
    reason        VARCHAR(20) NOT NULL CHECK (reason IN ('UPLOAD', 'DELETE')),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT statement_timestamp(),
    last_error    TEXT,
    UNIQUE (storage_scope, storage_key)
);
CREATE INDEX idx_material_object_cleanup_scope_session
    ON material_object_cleanup (storage_scope, session_id, id);
