-- V23 separated rejection_reason from correction review_notes for NEW decisions.
-- Older rejected records still keep the rejection in review_notes. Copy rather than
-- clear it: existing clients and the historical audit snapshots must retain their data.
-- Do not change V23's checksum on installations that have already applied it.
UPDATE project.projects
SET rejection_reason = review_notes
WHERE status = 'REJECTED'
  AND rejection_reason IS NULL
  AND review_notes IS NOT NULL;

-- Existing UNDER_REVIEW rows without a submission timestamp deliberately remain NULL.
-- There is no trustworthy evidence of who submitted them or when. The review queue
-- lists them last instead of fabricating a submission event from approval/creation.
