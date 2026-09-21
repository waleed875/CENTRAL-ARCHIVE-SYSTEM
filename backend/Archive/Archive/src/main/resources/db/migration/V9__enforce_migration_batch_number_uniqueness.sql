-- Migration batch identifiers are strings in the documented API/ERD.
-- Existing pre-Flyway installations may still have batch_number as INTEGER,
-- so normalize the column type before enforcing per-source uniqueness.
ALTER TABLE import.migration_batches
    ALTER COLUMN batch_number TYPE VARCHAR(80)
    USING batch_number::text;

ALTER TABLE import.migration_batches
    ADD CONSTRAINT uk_migration_batches_source_batch_number
    UNIQUE (migration_source_id, batch_number);
