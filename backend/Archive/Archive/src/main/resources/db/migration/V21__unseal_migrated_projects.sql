-- The legacy migration stamped every project it created with status ARCHIVED, which is the one
-- status that refuses writes: FileService.requireMutableProject, ProjectService.update and
-- ProjectMemberService.requireMutableProject all reject it. A migrated project is unfinished by
-- definition -- it lands with missing_fields set and data_completion_status = INCOMPLETE -- so
-- ARCHIVED locked out the exact corrections the missing_fields column exists to request.
--
-- Moved to IMPORTED, which is the state branch-package imports already use for "arrived, not yet
-- reviewed". From there a project can be corrected, marked COMPLETE and submitted for review.
--
-- Scoped to source_type = 'MIGRATION' so a project that was genuinely archived through the
-- approval workflow is untouched. A MIGRATION project cannot have reached APPROVED/ARCHIVED that
-- way, because it was born ARCHIVED, so the whole set is safe to move.
--
-- The status CHECK constraint in V1/V2 already allows IMPORTED, so nothing else has to change.
UPDATE project.projects
SET status = 'IMPORTED',
    updated_at = NOW()
WHERE status = 'ARCHIVED'
  AND source_type = 'MIGRATION';
