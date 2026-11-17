-- Preserve executable/uninspectable legacy originals without treating them as
-- downloadable active documents. Legacy migrations mark such records QUARANTINED.
-- The bytes remain in MinIO with a dedicated quarantine key prefix.
ALTER TABLE project.files
    ADD COLUMN quarantine_reason TEXT;

ALTER TABLE project.files DROP CONSTRAINT IF EXISTS files_status_check;
ALTER TABLE project.files ADD CONSTRAINT files_status_check
    CHECK (status IN ('ACTIVE', 'DELETED', 'REPLACED', 'QUARANTINED'));

CREATE INDEX IF NOT EXISTS idx_files_quarantined_created_at
    ON project.files (created_at DESC, id)
    WHERE status = 'QUARANTINED' AND deleted_at IS NULL;
