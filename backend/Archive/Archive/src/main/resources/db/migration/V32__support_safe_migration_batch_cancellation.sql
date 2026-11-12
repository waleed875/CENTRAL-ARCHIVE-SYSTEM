-- Persist safe cancellation requests and progress so a cancelled batch can be resumed.
ALTER TABLE import.migration_batches
    ADD COLUMN IF NOT EXISTS cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS total_projects_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_files_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS remaining_projects_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS remaining_files_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE import.migration_batches
    DROP CONSTRAINT IF EXISTS migration_batches_status_check;

ALTER TABLE import.migration_batches
    ADD CONSTRAINT migration_batches_status_check
    CHECK (status IN ('PLANNED', 'RUNNING', 'SUCCESS', 'FAILED', 'PARTIAL', 'CANCELLED'));
