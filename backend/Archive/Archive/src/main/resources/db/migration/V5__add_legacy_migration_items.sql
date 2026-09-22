-- Track resumable legacy project/file migration items.

CREATE TABLE IF NOT EXISTS import.legacy_migration_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind VARCHAR(20) NOT NULL CHECK (kind IN ('PROJECT','FILE')),
    source_key VARCHAR(255) NOT NULL,
    source_id BIGINT,
    target_id UUID,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','RUNNING','DONE','SKIPPED','FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    error TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_legacy_item_kind_source UNIQUE (kind, source_key)
);

CREATE INDEX IF NOT EXISTS idx_legacy_migration_items_kind_status
    ON import.legacy_migration_items(kind, status);
