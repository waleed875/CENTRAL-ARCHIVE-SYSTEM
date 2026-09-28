-- Tracks which mandatory fields a migrated project had to substitute, so the incomplete-data
-- report can list exactly what a reviewer still has to supply before approving the project.
ALTER TABLE project.projects
    ADD COLUMN IF NOT EXISTS missing_fields TEXT;

-- The incomplete-data report filters on this pair; a partial index keeps it cheap.
CREATE INDEX IF NOT EXISTS idx_projects_missing_fields
    ON project.projects (source_type, data_completion_status)
    WHERE missing_fields IS NOT NULL;
