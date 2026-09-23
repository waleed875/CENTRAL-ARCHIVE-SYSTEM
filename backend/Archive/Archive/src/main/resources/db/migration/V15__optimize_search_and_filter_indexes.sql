-- Improve substring search and common filtering paths for production-sized archives.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_projects_name_trgm
    ON project.projects USING gin (LOWER(name) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_projects_number_trgm
    ON project.projects USING gin (LOWER(project_number) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_projects_code_trgm
    ON project.projects USING gin (LOWER(project_code) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_projects_description_trgm
    ON project.projects USING gin (LOWER(description) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_projects_keywords_trgm
    ON project.projects USING gin (LOWER(keywords) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_projects_latest_org_year_status
    ON project.projects (is_latest_version, organization_id, year, status);

CREATE INDEX IF NOT EXISTS idx_projects_uid_version_desc
    ON project.projects (project_uid, version_no DESC);

CREATE INDEX IF NOT EXISTS idx_projects_confidentiality_latest
    ON project.projects (confidentiality_level_id, is_latest_version);

CREATE INDEX IF NOT EXISTS idx_files_original_name_trgm
    ON project.files USING gin (LOWER(original_name) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_files_extension_trgm
    ON project.files USING gin (LOWER(extension) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_files_project_active_filters
    ON project.files (project_id, deleted_at, category_id, type_id, status);

CREATE INDEX IF NOT EXISTS idx_files_confidentiality_active
    ON project.files (confidentiality_level_id, deleted_at);
