-- Align baseline lookup/reference tables with the current JPA entities.

ALTER TABLE core.roles
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE core.confidentiality_levels
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE project.project_types
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE project.file_categories
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE project.file_types
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE project.files
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- Migration batches are numbered sequentially in MigrationService and the
-- entity models batch_number as Integer. Convert any numeric legacy text.
DO $$
DECLARE current_type text;
BEGIN
    SELECT data_type INTO current_type
      FROM information_schema.columns
     WHERE table_schema='import'
       AND table_name='migration_batches'
       AND column_name='batch_number';

    IF current_type IS NOT NULL AND current_type NOT IN ('integer', 'bigint', 'smallint') THEN
        IF EXISTS (
            SELECT 1
              FROM import.migration_batches
             WHERE batch_number IS NOT NULL
               AND batch_number::text !~ '^[0-9]+$'
        ) THEN
            RAISE EXCEPTION 'Cannot migrate import.migration_batches.batch_number: non-numeric values exist';
        END IF;

        ALTER TABLE import.migration_batches
            ALTER COLUMN batch_number TYPE integer
            USING batch_number::integer;
    END IF;
END $$;
