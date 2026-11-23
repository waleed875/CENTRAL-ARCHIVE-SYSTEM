-- The administrator delete endpoint hides all versions, including approved and archived ones.
-- Draft-only cleanup and migrated-draft cleanup remain allowed by their existing cases.
ALTER TABLE project.projects DROP CONSTRAINT IF EXISTS ck_discarded_projects_only_drafts;
ALTER TABLE project.projects ADD CONSTRAINT ck_discarded_projects_only_drafts
    CHECK (
        discarded_at IS NULL
        OR status = 'DRAFT'
        OR (status = 'IMPORTED' AND source_type = 'MIGRATION')
        OR (discarded_by IS NOT NULL AND discard_reason IS NOT NULL AND btrim(discard_reason) <> '')
    );
