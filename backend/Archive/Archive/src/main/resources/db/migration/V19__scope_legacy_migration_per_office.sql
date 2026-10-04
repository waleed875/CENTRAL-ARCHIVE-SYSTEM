-- Per-office legacy connections and office-scoped migration idempotency.
--
-- The legacy system is installed once per organisation and every organisation owns a separate
-- database that shares the same ERD. Legacy primary keys (idproject, idside, idfile) are small
-- sequential integers, so they collide across organisations. Two global identifiers were built
-- from those bare keys and therefore collided:
--
--   * import.legacy_migration_items.source_key, guarded by uk_legacy_item_kind_source
--   * the deterministic project_uid derived from "LEGACY-" + idproject
--
-- A second organisation was consequently skipped as "already migrated" instead of failing, which
-- loses data silently. This migration makes the source the scope of both identifiers.

-- office_name is the discriminator chosen for organisation identity. Backfill before enforcing it
-- so existing sources stay valid, then require it: a source without an office cannot be migrated
-- safely because its rows would share an identity namespace with every other office.
UPDATE import.migration_sources
SET office_name = COALESCE(NULLIF(BTRIM(database_name), ''), name)
WHERE office_name IS NULL OR BTRIM(office_name) = '';

-- This guard is about rows that would end up unattributed, so it must only fire when there are
-- items to misattribute. migration_sources is a data table: on a brand new database it is empty,
-- and so is legacy_migration_items. Testing the sources alone therefore made this migration fail
-- on every fresh install, which meant the application could not start on an empty database at all.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM import.migration_sources)
       AND EXISTS (SELECT 1 FROM import.legacy_migration_items) THEN
        RAISE EXCEPTION 'no migration sources exist, so the existing migration items cannot be attributed to an office';
    END IF;
END
$$;

ALTER TABLE import.migration_sources
    ALTER COLUMN office_name SET NOT NULL;

CREATE UNIQUE INDEX uk_migration_sources_office_name ON import.migration_sources (office_name);

-- Connection details per office. Credentials differ per office, so the password is stored
-- encrypted (same AES-GCM envelope as the migration settings) rather than in the environment.
-- A source that leaves these blank keeps using the environment-configured legacy datasource,
-- which preserves the current single-database setup.
ALTER TABLE import.migration_sources
    ADD COLUMN host               varchar(200),
    ADD COLUMN port               integer,
    ADD COLUMN username           varchar(200),
    ADD COLUMN password_encrypted varchar(1000);

ALTER TABLE import.legacy_migration_items
    ADD COLUMN migration_source_id uuid REFERENCES import.migration_sources (id);

-- Every item recorded so far came from a database that was actually read, so attribute them to the
-- source of the most recently started batch rather than to whichever source happens to be oldest.
-- Picking by created_at alone would silently misfile the rows if the sources were ever reordered.
UPDATE import.legacy_migration_items
SET migration_source_id = COALESCE(
        (SELECT b.migration_source_id
           FROM import.migration_batches b
          WHERE b.started_at IS NOT NULL
          ORDER BY b.started_at DESC
          LIMIT 1),
        (SELECT s.id FROM import.migration_sources s ORDER BY s.created_at LIMIT 1))
WHERE migration_source_id IS NULL;

ALTER TABLE import.legacy_migration_items
    ALTER COLUMN migration_source_id SET NOT NULL;

-- The old uniqueness was a table constraint, not a standalone index, so it has to be dropped as
-- a constraint. Leaving it in place would keep rejecting the second office's identical keys.
ALTER TABLE import.legacy_migration_items
    DROP CONSTRAINT uk_legacy_item_kind_source;

CREATE UNIQUE INDEX uk_legacy_item_source_kind_source
    ON import.legacy_migration_items (migration_source_id, kind, source_key);

-- The previous (kind, status) index still serves the report queries, so it is kept.
CREATE INDEX idx_legacy_migration_items_source_kind_status
    ON import.legacy_migration_items (migration_source_id, kind, status);
