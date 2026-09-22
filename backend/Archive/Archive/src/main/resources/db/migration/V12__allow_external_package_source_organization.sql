-- Branch package source organization IDs originate from offline branch systems.
-- They are external identifiers and must not be constrained to central core.organizations.
-- Central organization binding happens explicitly during import through organization_mapping.

DO $$
DECLARE
    constraint_name text;
BEGIN
    FOR constraint_name IN
        SELECT c.conname
          FROM pg_constraint c
          JOIN pg_class t ON t.oid = c.conrelid
          JOIN pg_namespace n ON n.oid = t.relnamespace
          JOIN pg_attribute a
            ON a.attrelid = t.oid
           AND a.attnum = ANY(c.conkey)
         WHERE n.nspname = 'import'
           AND t.relname = 'branch_packages'
           AND c.contype = 'f'
           AND a.attname = 'source_organization_id'
    LOOP
        EXECUTE format(
            'ALTER TABLE import.branch_packages DROP CONSTRAINT %I',
            constraint_name
        );
    END LOOP;
END $$;
