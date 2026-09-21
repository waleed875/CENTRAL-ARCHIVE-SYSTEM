-- Align project member timestamps with the JPA entity.
ALTER TABLE project.project_members
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
