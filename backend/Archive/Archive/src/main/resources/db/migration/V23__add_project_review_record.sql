-- Records who sent a project into review and when, and gives the rejection reason a column of its
-- own instead of sharing review_notes with the correction instruction.
--
-- review_notes was carrying three different things. requestCorrection wrote the instruction the
-- person fixing the project has to follow. reject overwrote that same column with the rejection
-- reason. submitForReview then cleared the column outright, so the correction round destroyed its
-- own instruction: the note survived only in the audit log's JSON snapshot, which nobody opens while
-- doing the work. rejection_reason takes the rejection; review_notes keeps the correction and now
-- stays put across a resubmission.
--
-- submitted_by and submitted_at give a review queue something to order by. Without them "under
-- review" has no age — a project submitted this morning and one that has been waiting since last
-- month read identically, and the requirements report's own "المراجعة بانتظار" station had nothing
-- behind it.
--
-- Backfill so existing rows are not silently absent from the queue: approved_by/approved_at are the
-- only submission evidence the table has ever held, so a project already past review is stamped
-- from them rather than left looking never-submitted.
ALTER TABLE project.projects
    ADD COLUMN IF NOT EXISTS rejection_reason TEXT,
    ADD COLUMN IF NOT EXISTS submitted_by UUID REFERENCES core.users(id),
    ADD COLUMN IF NOT EXISTS submitted_at TIMESTAMPTZ;

-- idx_projects_status already covers filtering on the status alone. A queue wants the waiting time
-- ordered within UNDER_REVIEW, and a partial index keeps that cheap: the predicate is the one state
-- a reviewer works on, and the index stays small however many archived projects accumulate.
CREATE INDEX IF NOT EXISTS idx_projects_awaiting_review
    ON project.projects (submitted_at)
    WHERE status = 'UNDER_REVIEW';

-- Projects that have already been decided carry an approval but no submission stamp. Reusing the
-- approval is an approximation, not a fact — it is only right for projects approved first time, and
-- it is better than a null that reads as "never submitted".
UPDATE project.projects
    SET submitted_by = approved_by,
        submitted_at = approved_at
    WHERE submitted_at IS NULL
      AND approved_at IS NOT NULL
      AND status IN ('APPROVED', 'ARCHIVED');