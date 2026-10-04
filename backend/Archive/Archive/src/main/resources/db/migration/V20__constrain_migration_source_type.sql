-- import.migration_sources.source_type became a Java enum (MigrationSourceType). The column was
-- created as a free VARCHAR(60) with no constraint, so a typo written through the old string DTO is
-- sitting in a deployed database and Hibernate cannot map it to the enum. The rows are named here
-- rather than coerced: a source whose type nobody can explain is a source an operator must decide
-- about, and silently rewriting it to LEGACY_DB would point a migration at a database nobody chose.
DO $$
DECLARE
    offending TEXT;
BEGIN
    SELECT string_agg(source_type || ' (id=' || id || ', name=' || name || ')', ', ')
      INTO offending
      FROM import.migration_sources
     WHERE source_type IS NOT NULL
       AND BTRIM(source_type) <> ''
       AND UPPER(BTRIM(source_type)) NOT IN ('POSTGRESQL', 'LEGACY_DB');

    IF offending IS NOT NULL THEN
        RAISE EXCEPTION
            'import.migration_sources holds % source row(s) whose type is not POSTGRESQL or LEGACY_DB: %. '
            'Update them by hand before starting the application, or delete them.',
            (SELECT COUNT(*) FROM import.migration_sources
              WHERE source_type IS NOT NULL
                AND BTRIM(source_type) <> ''
                AND UPPER(BTRIM(source_type)) NOT IN ('POSTGRESQL', 'LEGACY_DB')),
            offending;
    END IF;
END
$$;

-- Written uppercase because the enum names are. A lowercase value is normalised rather than
-- rejected: it was a valid choice before this migration, only spelled differently.
UPDATE import.migration_sources
SET source_type = UPPER(BTRIM(source_type))
WHERE source_type IS NOT NULL
  AND BTRIM(source_type) <> ''
  AND source_type <> UPPER(BTRIM(source_type));

-- Nullable on purpose: a source that defers to legacy.datasource.* never needed a type, and
-- MigrationRunner only ever stamps one on the source it creates. Forcing NOT NULL would reject
-- those pre-existing rows for a field the application never had to read.
ALTER TABLE import.migration_sources
    DROP CONSTRAINT IF EXISTS ck_migration_sources_source_type;

ALTER TABLE import.migration_sources
    ADD CONSTRAINT ck_migration_sources_source_type
        CHECK (
            source_type IS NULL
            OR source_type IN ('POSTGRESQL', 'LEGACY_DB')
        );
