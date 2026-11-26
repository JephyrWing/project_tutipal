-- PT-106 / PT-13 PM decision (2026-11-26): AI conversation retention.
-- A. Class conversations (session_id NOT NULL): the teacher sets a retention period per study session (0..90 days,
--    counted from ended_at). After ended_at + ai_retention_days the session's conversations (messages / summaries by
--    ON DELETE CASCADE) and its session-scoped memories are physically deleted, except conversations the student saved.
-- B. Personal conversations (session_id IS NULL): deleted app.ai.conversations.personal-retention-days (30) after
--    last_message_at (= created_at while there is no message), except saved ones.
-- C. saved_at: set by the owner's "저장"; a saved conversation stays until the user deletes it (soft delete + grace).

-- Required on create (CreateSessionRequest @NotNull); nullable only for rows that existed before V24.
ALTER TABLE study_sessions
    ADD COLUMN ai_retention_days SMALLINT
        CONSTRAINT ck_study_sessions_ai_retention_days CHECK (ai_retention_days BETWEEN 0 AND 90);

-- Existing sessions get the maximum (90) so nothing stored before this decision is deleted earlier than allowed.
-- The retention job also treats a NULL (rows inserted outside the API) as 90.
UPDATE study_sessions SET ai_retention_days = 90 WHERE ai_retention_days IS NULL;

ALTER TABLE ai_conversations
    ADD COLUMN saved_at TIMESTAMP(6) WITH TIME ZONE;

-- The old inactivity index covered every row; retention now only scans unsaved rows by scope.
DROP INDEX IF EXISTS idx_ai_conversations_last_message;

-- B. personal retention candidates (live and soft-deleted alike), oldest activity first
CREATE INDEX idx_ai_conversations_personal_idle
    ON ai_conversations (last_message_at, id) WHERE session_id IS NULL AND saved_at IS NULL;
-- A. class retention candidates, looked up per expired session
CREATE INDEX idx_ai_conversations_class_unsaved
    ON ai_conversations (session_id, id) WHERE session_id IS NOT NULL AND saved_at IS NULL;
-- A. ended sessions (ended_at < now is the sargable part of ended_at + retention < now)
CREATE INDEX idx_study_sessions_ended
    ON study_sessions (ended_at) WHERE ended_at IS NOT NULL;
