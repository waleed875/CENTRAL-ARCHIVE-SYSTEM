ALTER TABLE import.migration_sources
    ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN import.migration_sources.is_active IS
    'Whether this source can be used for new migration batches; inactive sources retain historical batches and audit references.';
