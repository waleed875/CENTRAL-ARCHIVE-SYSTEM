-- Compatibility migration for databases created by the pre-Flyway JPA model.
-- This migration is intentionally additive/conservative where possible.
-- Unimported legacy package_projects rows are removed because the old model used
-- that table as staging before a central project existed. The authoritative
-- manifest remains stored on branch_packages and can be re-verified/re-imported.

CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS project;
CREATE SCHEMA IF NOT EXISTS import;
CREATE SCHEMA IF NOT EXISTS audit;

-- ---------------------------------------------------------------------------
-- project.projects.project_uid: VARCHAR -> UUID and versioned uniqueness
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    current_type text;
    constraint_name text;
BEGIN
    SELECT data_type
      INTO current_type
      FROM information_schema.columns
     WHERE table_schema = 'project'
       AND table_name = 'projects'
       AND column_name = 'project_uid';

    IF current_type IS NOT NULL AND current_type <> 'uuid' THEN
        EXECUTE $sql$
            ALTER TABLE project.projects
            ALTER COLUMN project_uid TYPE uuid
            USING (
                CASE
                    WHEN project_uid IS NULL THEN gen_random_uuid()
                    WHEN project_uid ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                        THEN project_uid::uuid
                    ELSE (
                        substr(md5(project_uid), 1, 8) || '-' ||
                        substr(md5(project_uid), 9, 4) || '-' ||
                        substr(md5(project_uid), 13, 4) || '-' ||
                        substr(md5(project_uid), 17, 4) || '-' ||
                        substr(md5(project_uid), 21, 12)
                    )::uuid
                END
            )
        $sql$;
    END IF;

    FOR constraint_name IN
        SELECT c.conname
          FROM pg_constraint c
          JOIN pg_class t ON t.oid = c.conrelid
          JOIN pg_namespace n ON n.oid = t.relnamespace
         WHERE n.nspname = 'project'
           AND t.relname = 'projects'
           AND c.contype = 'u'
           AND array_length(c.conkey, 1) = 1
           AND c.conkey[1] = (
               SELECT attnum FROM pg_attribute
                WHERE attrelid = t.oid AND attname = 'project_uid'
           )
    LOOP
        EXECUTE format('ALTER TABLE project.projects DROP CONSTRAINT %I', constraint_name);
    END LOOP;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint c
          JOIN pg_class t ON t.oid = c.conrelid
          JOIN pg_namespace n ON n.oid = t.relnamespace
         WHERE n.nspname = 'project'
           AND t.relname = 'projects'
           AND c.conname = 'uk_projects_project_uid_version'
    ) THEN
        ALTER TABLE project.projects
            ADD CONSTRAINT uk_projects_project_uid_version
            UNIQUE (project_uid, version_no);
    END IF;
END $$;

-- Ensure REJECTED is accepted by the project workflow constraint.
DO $$
DECLARE
    constraint_name text;
BEGIN
    FOR constraint_name IN
        SELECT c.conname
          FROM pg_constraint c
          JOIN pg_class t ON t.oid = c.conrelid
          JOIN pg_namespace n ON n.oid = t.relnamespace
         WHERE n.nspname = 'project'
           AND t.relname = 'projects'
           AND c.contype = 'c'
           AND pg_get_constraintdef(c.oid) ILIKE '%status%'
    LOOP
        EXECUTE format('ALTER TABLE project.projects DROP CONSTRAINT %I', constraint_name);
    END LOOP;

    ALTER TABLE project.projects
        ADD CONSTRAINT chk_projects_status
        CHECK (status IN ('DRAFT','IMPORTED','UNDER_REVIEW','NEEDS_CORRECTION','REJECTED','APPROVED','ARCHIVED'));
END $$;

-- ---------------------------------------------------------------------------
-- Actor columns: legacy usernames/text -> core.users UUID references
-- ---------------------------------------------------------------------------
DO $$
DECLARE current_type text;
BEGIN
    SELECT data_type INTO current_type
      FROM information_schema.columns
     WHERE table_schema='project' AND table_name='projects' AND column_name='created_by';

    IF current_type IS NOT NULL AND current_type <> 'uuid' THEN
        ALTER TABLE project.projects ADD COLUMN IF NOT EXISTS created_by_uuid uuid;
        UPDATE project.projects p
           SET created_by_uuid = u.id
          FROM core.users u
         WHERE p.created_by_uuid IS NULL
           AND (p.created_by = u.username OR p.created_by = u.id::text);
        ALTER TABLE project.projects DROP COLUMN created_by;
        ALTER TABLE project.projects RENAME COLUMN created_by_uuid TO created_by;
    END IF;

    SELECT data_type INTO current_type
      FROM information_schema.columns
     WHERE table_schema='project' AND table_name='projects' AND column_name='approved_by';

    IF current_type IS NOT NULL AND current_type <> 'uuid' THEN
        ALTER TABLE project.projects ADD COLUMN IF NOT EXISTS approved_by_uuid uuid;
        UPDATE project.projects p
           SET approved_by_uuid = u.id
          FROM core.users u
         WHERE p.approved_by_uuid IS NULL
           AND (p.approved_by = u.username OR p.approved_by = u.id::text);
        ALTER TABLE project.projects DROP COLUMN approved_by;
        ALTER TABLE project.projects RENAME COLUMN approved_by_uuid TO approved_by;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- import.branch_packages alignment
-- ---------------------------------------------------------------------------
ALTER TABLE import.branch_packages
    ADD COLUMN IF NOT EXISTS verified_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS imported_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS error_message TEXT,
    ADD COLUMN IF NOT EXISTS manifest_data TEXT,
    ADD COLUMN IF NOT EXISTS target_organization_id UUID REFERENCES core.organizations(id),
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;

DO $$
DECLARE current_type text;
BEGIN
    SELECT data_type INTO current_type
      FROM information_schema.columns
     WHERE table_schema='import' AND table_name='branch_packages' AND column_name='package_uuid';
    IF current_type IS NOT NULL AND current_type <> 'uuid' THEN
        ALTER TABLE import.branch_packages
        ALTER COLUMN package_uuid TYPE uuid
        USING (
            CASE
                WHEN package_uuid ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                    THEN package_uuid::uuid
                ELSE (
                    substr(md5(package_uuid),1,8)||'-'||substr(md5(package_uuid),9,4)||'-'||
                    substr(md5(package_uuid),13,4)||'-'||substr(md5(package_uuid),17,4)||'-'||
                    substr(md5(package_uuid),21,12)
                )::uuid
            END
        );
    END IF;

    SELECT data_type INTO current_type
      FROM information_schema.columns
     WHERE table_schema='import' AND table_name='branch_packages' AND column_name='source_terminal_uuid';
    IF current_type IS NOT NULL AND current_type <> 'uuid' THEN
        ALTER TABLE import.branch_packages
        ALTER COLUMN source_terminal_uuid TYPE uuid
        USING (
            CASE
                WHEN source_terminal_uuid ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                    THEN source_terminal_uuid::uuid
                ELSE (
                    substr(md5(source_terminal_uuid),1,8)||'-'||substr(md5(source_terminal_uuid),9,4)||'-'||
                    substr(md5(source_terminal_uuid),13,4)||'-'||substr(md5(source_terminal_uuid),17,4)||'-'||
                    substr(md5(source_terminal_uuid),21,12)
                )::uuid
            END
        );
    END IF;

    SELECT data_type INTO current_type
      FROM information_schema.columns
     WHERE table_schema='import' AND table_name='branch_packages' AND column_name='received_by';
    IF current_type IS NOT NULL AND current_type <> 'uuid' THEN
        ALTER TABLE import.branch_packages ADD COLUMN IF NOT EXISTS received_by_uuid uuid;
        UPDATE import.branch_packages bp
           SET received_by_uuid = u.id
          FROM core.users u
         WHERE bp.received_by_uuid IS NULL
           AND (bp.received_by = u.username OR bp.received_by = u.id::text);
        ALTER TABLE import.branch_packages DROP COLUMN received_by;
        ALTER TABLE import.branch_packages RENAME COLUMN received_by_uuid TO received_by;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- package_projects old semantics -> documented central/source semantics
-- ---------------------------------------------------------------------------
ALTER TABLE import.package_projects
    ADD COLUMN IF NOT EXISTS source_project_number VARCHAR(80),
    ADD COLUMN IF NOT EXISTS project_name VARCHAR(250),
    ADD COLUMN IF NOT EXISTS project_year INTEGER,
    ADD COLUMN IF NOT EXISTS import_error TEXT,
    ADD COLUMN IF NOT EXISTS files_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS files_imported INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS files_failed INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();

DO $$
DECLARE current_type text;
BEGIN
    SELECT data_type INTO current_type
      FROM information_schema.columns
     WHERE table_schema='import' AND table_name='package_projects' AND column_name='project_id';

    IF current_type IS NOT NULL AND current_type <> 'uuid' THEN
        ALTER TABLE import.package_projects ADD COLUMN project_id_new uuid;
        ALTER TABLE import.package_projects ADD COLUMN local_project_uuid_new uuid;

        -- In the legacy code, local_project_uuid held the central project UUID
        -- after import, while project_id held the terminal/source identifier.
        UPDATE import.package_projects
           SET project_id_new = local_project_uuid,
               local_project_uuid_new =
                   CASE
                       WHEN project_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                           THEN project_id::uuid
                       ELSE (
                           substr(md5(project_id),1,8)||'-'||substr(md5(project_id),9,4)||'-'||
                           substr(md5(project_id),13,4)||'-'||substr(md5(project_id),17,4)||'-'||
                           substr(md5(project_id),21,12)
                       )::uuid
                   END;

        -- Rows without a central project were staging-only rows. Their source
        -- data remains in branch_packages.manifest_data and can be re-imported.
        DELETE FROM import.package_projects WHERE project_id_new IS NULL;

        ALTER TABLE import.package_projects DROP COLUMN project_id;
        ALTER TABLE import.package_projects DROP COLUMN local_project_uuid;
        ALTER TABLE import.package_projects RENAME COLUMN project_id_new TO project_id;
        ALTER TABLE import.package_projects RENAME COLUMN local_project_uuid_new TO local_project_uuid;

        ALTER TABLE import.package_projects ALTER COLUMN project_id SET NOT NULL;
        ALTER TABLE import.package_projects ALTER COLUMN local_project_uuid SET NOT NULL;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname='fk_package_projects_project'
           AND conrelid='import.package_projects'::regclass
    ) THEN
        ALTER TABLE import.package_projects
            ADD CONSTRAINT fk_package_projects_project
            FOREIGN KEY (project_id) REFERENCES project.projects(id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname='uk_package_projects_package_project'
           AND conrelid='import.package_projects'::regclass
    ) THEN
        ALTER TABLE import.package_projects
            ADD CONSTRAINT uk_package_projects_package_project
            UNIQUE (package_id, project_id);
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- export_packages introduced after the original MVP baseline
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS import.export_packages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    package_uuid UUID NOT NULL UNIQUE,
    package_name VARCHAR(300) NOT NULL,
    schema_version VARCHAR(30) NOT NULL,
    source_organization_id UUID REFERENCES core.organizations(id),
    source_username VARCHAR(120),
    projects_count INTEGER NOT NULL DEFAULT 0,
    files_count INTEGER NOT NULL DEFAULT 0,
    package_hash CHAR(64),
    manifest_hash CHAR(64),
    package_object_key TEXT,
    manifest_data TEXT,
    status VARCHAR(40) NOT NULL DEFAULT 'CREATED' CHECK (status IN ('CREATED','EXPORTED','FAILED')),
    exported_by UUID REFERENCES core.users(id),
    exported_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_branch_packages_status ON import.branch_packages(status);
CREATE INDEX IF NOT EXISTS idx_projects_uid_latest ON project.projects(project_uid, is_latest_version);
CREATE INDEX IF NOT EXISTS idx_projects_status ON project.projects(status);
CREATE INDEX IF NOT EXISTS idx_files_project ON project.files(project_id);
CREATE INDEX IF NOT EXISTS idx_files_checksum ON project.files(checksum_sha256);
