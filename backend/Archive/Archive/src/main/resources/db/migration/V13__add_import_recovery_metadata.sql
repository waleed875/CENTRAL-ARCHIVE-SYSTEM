-- Add crash-recovery and provenance metadata for package imports.
ALTER TABLE import.import_operations
    ADD COLUMN IF NOT EXISTS last_heartbeat_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS request_snapshot TEXT,
    ADD COLUMN IF NOT EXISTS retry_of UUID;

UPDATE import.import_operations
   SET last_heartbeat_at = COALESCE(last_heartbeat_at, started_at, now())
 WHERE last_heartbeat_at IS NULL;

ALTER TABLE import.import_operations
    ALTER COLUMN last_heartbeat_at SET DEFAULT now(),
    ALTER COLUMN last_heartbeat_at SET NOT NULL;

ALTER TABLE import.import_operations
    DROP CONSTRAINT IF EXISTS import_operations_status_check;

ALTER TABLE import.import_operations
    DROP CONSTRAINT IF EXISTS chk_import_operations_status;

ALTER TABLE import.import_operations
    ADD CONSTRAINT chk_import_operations_status
    CHECK (status IN ('STARTED','SUCCESS','FAILED','PARTIAL','INTERRUPTED'));

ALTER TABLE import.import_operations
    DROP CONSTRAINT IF EXISTS fk_import_operations_retry_of;

ALTER TABLE import.import_operations
    ADD CONSTRAINT fk_import_operations_retry_of
    FOREIGN KEY (retry_of)
    REFERENCES import.import_operations(id)
    ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_import_operations_retry_of
    ON import.import_operations(retry_of);

CREATE INDEX IF NOT EXISTS idx_import_operations_package_started_at
    ON import.import_operations(package_id, started_at DESC);
