-- Runtime-editable legacy migration switches.
--
-- These two values are the only environment settings the UI is allowed to change. They are read on
-- every migration attempt instead of at bean construction, so toggling them from the settings screen
-- takes effect without restarting the application. Everything else (JDBC url, credentials, MinIO)
-- stays in the environment and is never writable through the API.
CREATE TABLE IF NOT EXISTS import.migration_settings (
    id UUID PRIMARY KEY,
    -- All rows carry TRUE, so this unique index caps the table at exactly one row.
    singleton BOOLEAN NOT NULL DEFAULT TRUE,
    -- FALSE means "inherit the environment value". Flipped to TRUE on the first save from the UI so
    -- the row can always be distinguished from one that was only ever seeded by this migration.
    configured BOOLEAN NOT NULL DEFAULT FALSE,
    migration_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    default_confidentiality_code_encrypted TEXT,
    updated_at TIMESTAMP(6) NOT NULL,
    updated_by UUID REFERENCES core.users (id) ON DELETE SET NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_migration_settings_singleton UNIQUE (singleton)
);

-- Seed the singleton row so reads never have to handle a missing table content.
INSERT INTO import.migration_settings (id, singleton, migration_enabled, updated_at)
VALUES (gen_random_uuid(), TRUE, FALSE, NOW())
ON CONFLICT (singleton) DO NOTHING;

-- Idempotent backfill for databases where an earlier revision of this migration had already been
-- applied. CREATE TABLE IF NOT EXISTS does not add columns to a table that already exists, so without
-- these the entity would fail against such a database.
ALTER TABLE import.migration_settings
    ADD COLUMN IF NOT EXISTS configured BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE import.migration_settings
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
