-- Persist per-import project outcome statistics independently from central project creation.
ALTER TABLE import.import_operations
    ADD COLUMN IF NOT EXISTS projects_total INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS projects_imported INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS projects_partial INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS projects_failed INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS projects_duplicate INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS projects_skipped INTEGER NOT NULL DEFAULT 0;

ALTER TABLE import.import_operations
    ADD CONSTRAINT chk_import_operation_project_counters_nonnegative
    CHECK (
        projects_total >= 0
        AND projects_imported >= 0
        AND projects_partial >= 0
        AND projects_failed >= 0
        AND projects_duplicate >= 0
        AND projects_skipped >= 0
    );
