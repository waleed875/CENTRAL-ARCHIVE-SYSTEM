-- READ ONLY. Run on a restored staging copy before applying V28.
BEGIN READ ONLY;

-- Must return no rows before adding the completion-state check.
SELECT id, data_completion_status
FROM project.projects
WHERE data_completion_status IS NOT NULL
  AND data_completion_status NOT IN ('COMPLETE','INCOMPLETE');

-- Must return no rows before enforcing composite file type/category references.
SELECT f.id, f.category_id, f.type_id, t.category_id AS expected_category_id
FROM project.files f JOIN project.file_types t ON t.id=f.type_id
WHERE f.category_id IS DISTINCT FROM t.category_id;

-- Must return no rows before enforcing source-project identity in packages.
SELECT package_id, local_project_uuid, COUNT(*) AS conflicting_rows
FROM import.package_projects
GROUP BY package_id, local_project_uuid HAVING COUNT(*)>1;

SELECT id, singleton FROM import.migration_settings WHERE singleton IS NOT TRUE;

-- Diagnostic only: these latest-version findings are not repaired by V28.
SELECT project_uid, COUNT(*) AS latest_rows
FROM project.projects WHERE is_latest_version
GROUP BY project_uid HAVING COUNT(*)>1;
SELECT organization_id, project_number, year, COUNT(*) AS latest_identities
FROM project.projects WHERE is_latest_version
GROUP BY organization_id, project_number, year HAVING COUNT(*)>1;

ROLLBACK;
