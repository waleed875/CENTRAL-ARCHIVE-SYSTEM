-- Retain legally relevant file deletion history while removing a discarded draft
-- from operational project searches. No project or file record is purged.
ALTER TABLE project.projects
    ADD COLUMN discarded_at TIMESTAMPTZ;

ALTER TABLE project.projects
    ADD CONSTRAINT ck_discarded_projects_only_drafts
        CHECK (discarded_at IS NULL OR status = 'DRAFT');

CREATE INDEX ix_projects_visible_latest
    ON project.projects (is_latest_version, created_at DESC)
    WHERE discarded_at IS NULL;

COMMENT ON COLUMN project.projects.discarded_at IS
    'Soft-discard timestamp for unused drafts retaining soft-deleted file history. Retained records are not physically deleted.';
