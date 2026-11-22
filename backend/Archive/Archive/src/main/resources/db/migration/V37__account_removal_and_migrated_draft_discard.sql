-- Account removal retains the original immutable actor UUID for historical foreign keys.
ALTER TABLE core.users DROP CONSTRAINT IF EXISTS users_status_check;
ALTER TABLE core.users ADD CONSTRAINT users_status_check
    CHECK (status IN ('ACTIVE','DISABLED','LOCKED','DELETED'));
ALTER TABLE core.users ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
ALTER TABLE core.users ADD COLUMN IF NOT EXISTS deleted_by UUID REFERENCES core.users(id);
ALTER TABLE core.users ADD COLUMN IF NOT EXISTS deletion_reason VARCHAR(1000);

-- Discarding migrated drafts preserves the imported files, source IDs, and MinIO objects.
ALTER TABLE project.projects ADD COLUMN IF NOT EXISTS discarded_by UUID REFERENCES core.users(id);
ALTER TABLE project.projects ADD COLUMN IF NOT EXISTS discard_reason VARCHAR(1000);
