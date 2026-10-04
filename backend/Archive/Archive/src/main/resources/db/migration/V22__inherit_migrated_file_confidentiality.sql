-- Migrated files used to be stamped with a run-wide default confidentiality code while their
-- project was migrated with no level of its own. A file classified more strictly than the project
-- holding it is dropped from the file listing by any viewer whose clearance does not reach it, and
-- the drop is silent: the project opens to an empty file list with no error to explain it. The
-- bootstrap admin has no max_confidentiality_level_id, so currentMaxLevelOrder() is null and every
-- migrated file was filtered out.
--
-- Files now inherit their project's level, so the two agree by construction. This realigns rows
-- already written under the old rule.
--
-- Scoped to source_type = 'MIGRATION' so hand-created and branch-package files keep whatever
-- classification they were given. IS DISTINCT FROM is null-safe, so this also clears a stale stamp
-- on a project an administrator has since classified and then un-classified again, and leaves a
-- file that already matches its project completely alone -- no spurious updated_at, no row churn.
UPDATE project.files f
SET confidentiality_level_id = p.confidentiality_level_id
FROM project.projects p
WHERE p.id = f.project_id
  AND f.source_type = 'MIGRATION'
  AND f.confidentiality_level_id IS DISTINCT FROM p.confidentiality_level_id;
