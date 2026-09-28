-- The legacy migration substitutes UNKNOWN_YEAR (1000) for a project whose year was missing or
-- implausible, so the record can still enter the platform while the incomplete-data report flags
-- that the year still needs a real value.
--
-- The original V1 constraint was a blanket "year >= 1900". Widening it blindly would let any project
-- store 1000, so the sentinel is permitted only where it is actually produced: a MIGRATION row.
-- Manual projects keep the original guarantee, and the API layer already rejects 1000 for them via
-- @Min(1900) on ProjectRequest / ProjectUpdateRequest.
ALTER TABLE project.projects
    DROP CONSTRAINT IF EXISTS projects_year_check;

ALTER TABLE project.projects
    ADD CONSTRAINT projects_year_check
        CHECK (year >= 1900 OR (year = 1000 AND source_type = 'MIGRATION'));

COMMENT ON COLUMN project.projects.year IS
    'Project year. 1000 is the migration sentinel for a missing/implausible legacy year and is only valid on MIGRATION rows.';
