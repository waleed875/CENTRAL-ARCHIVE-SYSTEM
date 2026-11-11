-- Preserve all legacy projects even when the source reused a project number within
-- the same organization/year/version. The uniqueness rule remains enforced for projects
-- created through normal workflows and package imports; only MIGRATION rows are exempt.
--
-- project_uid/version_no remains unique for every row, and legacy project_uid is deterministic
-- per office + source project id, so reruns remain idempotent.
ALTER TABLE project.projects
    DROP CONSTRAINT IF EXISTS projects_organization_id_project_number_year_version_no_key;

CREATE UNIQUE INDEX IF NOT EXISTS uk_projects_org_number_year_version_non_migration
    ON project.projects (organization_id, project_number, year, version_no)
    WHERE source_type IS DISTINCT FROM 'MIGRATION';

COMMENT ON INDEX project.uk_projects_org_number_year_version_non_migration IS
    'Preserves project number uniqueness for non-migrated projects; legacy imports may contain duplicates.';
