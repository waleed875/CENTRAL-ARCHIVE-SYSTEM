-- Migration batch identifiers are strings in the documented API/ERD
-- and must be unique within each migration source.
ALTER TABLE import.migration_batches
    ADD CONSTRAINT uk_migration_batches_source_batch_number
    UNIQUE (migration_source_id, batch_number);
