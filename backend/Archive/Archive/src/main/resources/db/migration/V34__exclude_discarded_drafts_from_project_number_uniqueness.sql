-- A cancelled manual draft can keep historical deleted-file rows without reserving
-- its project number forever. All active non-migration project numbers remain unique.
-- V31 created this index; replace its predicate without modifying any project rows.
DROP INDEX IF EXISTS project.uk_projects_org_number_year_version_non_migration;

CREATE UNIQUE INDEX uk_projects_org_number_year_version_non_migration
    ON project.projects (organization_id, project_number, year, version_no)
    WHERE source_type IS DISTINCT FROM 'MIGRATION' AND discarded_at IS NULL;

COMMENT ON INDEX project.uk_projects_org_number_year_version_non_migration IS
    'Unique for non-migrated projects that have not been discarded; retained deleted-file records do not reserve numbers.';
