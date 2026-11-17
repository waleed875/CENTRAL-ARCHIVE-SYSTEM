-- READ-ONLY migration diagnostics. Run against a replica or a verified backup.
-- This script does not list MinIO buckets: the object keys below are PostgreSQL
-- claims and must be checked with the MinIO SDK separately.
-- Do not infer successful migration from a MinIO object without project.files.
BEGIN TRANSACTION READ ONLY;

-- 1. Migration tracking status (by source/entry type).
SELECT migration_source_id, kind, status, COUNT(*) AS items
FROM import.legacy_migration_items
GROUP BY migration_source_id, kind, status
ORDER BY migration_source_id, kind, status;

-- 2. A file is NOT usable merely because its migration item says DONE.
-- The metadata row must exist, be active, and reference a real project.
SELECT i.migration_source_id, i.source_key, i.status, i.target_id,
       i.error, i.attempts, i.finished_at,
       f.id AS file_record_id, f.project_id,
       p.id AS project_record_id, f.bucket_name, f.object_key
FROM import.legacy_migration_items i
LEFT JOIN project.files f ON f.id = i.target_id
LEFT JOIN project.projects p ON p.id = f.project_id
WHERE i.kind = 'FILE'
  AND (i.status <> 'DONE'
       OR i.target_id IS NULL
       OR f.id IS NULL
       OR p.id IS NULL
       OR f.deleted_at IS NOT NULL
       OR UPPER(f.status) NOT IN ('ACTIVE', 'QUARANTINED'))
ORDER BY i.migration_source_id, i.source_key
LIMIT 1000;

-- 3. Count source-file tracking records and real linked file rows.
SELECT i.migration_source_id,
       COUNT(*) FILTER (WHERE i.status = 'DONE') AS tracked_done,
       COUNT(*) FILTER (WHERE i.status = 'DONE' AND f.id IS NOT NULL
         AND p.id IS NOT NULL AND f.deleted_at IS NULL
         AND UPPER(f.status) IN ('ACTIVE', 'QUARANTINED')) AS linked_active,
       COUNT(*) FILTER (WHERE i.status = 'RUNNING') AS tracked_running,
       COUNT(*) FILTER (WHERE i.status = 'FAILED') AS tracked_failed
FROM import.legacy_migration_items i
LEFT JOIN project.files f ON f.id = i.target_id
LEFT JOIN project.projects p ON p.id = f.project_id
WHERE i.kind = 'FILE'
GROUP BY i.migration_source_id
ORDER BY i.migration_source_id;

-- 4. Files uploaded by migration but not linked to an existing project.
SELECT f.id, f.project_id, f.original_name, f.bucket_name, f.object_key,
       f.source_reference, f.status, f.deleted_at
FROM project.files f
LEFT JOIN project.projects p ON p.id = f.project_id
WHERE f.source_type = 'MIGRATION' AND p.id IS NULL
LIMIT 1000;

-- 5. Project metadata file counts (not MinIO object inventory).
SELECT p.id AS project_id, p.project_number, p.name, p.year,
       COUNT(f.id) FILTER (
           WHERE f.deleted_at IS NULL AND UPPER(f.status) IN ('ACTIVE', 'QUARANTINED')
       ) AS active_files
FROM project.projects p
LEFT JOIN project.files f ON f.project_id = p.id
WHERE p.source_type = 'MIGRATION'
GROUP BY p.id, p.project_number, p.name, p.year
ORDER BY active_files ASC, p.project_number ASC
LIMIT 1000;

-- 6. Storage keys for investigation by an administrator with MinIO credentials.
-- Use this output only in a controlled environment. Do not publicly share keys.
SELECT f.id, f.bucket_name, f.object_key, f.size_bytes, f.checksum_sha256
FROM project.files f
WHERE f.source_type = 'MIGRATION'
  AND f.deleted_at IS NULL
ORDER BY f.created_at DESC
LIMIT 1000;

ROLLBACK;
