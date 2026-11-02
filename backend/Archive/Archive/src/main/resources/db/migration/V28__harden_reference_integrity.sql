-- Forward-only integrity fixes. Never silently delete/merge archived data.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM import.package_projects
               GROUP BY package_id, local_project_uuid HAVING COUNT(*) > 1) THEN
        RAISE EXCEPTION 'Duplicate package/source-project identities exist. Reconcile them before applying V28.';
    END IF;
    IF EXISTS (SELECT 1 FROM project.files f JOIN project.file_types t ON t.id=f.type_id
               WHERE f.category_id IS DISTINCT FROM t.category_id) THEN
        RAISE EXCEPTION 'File type/category mismatches exist. Reconcile them before applying V28.';
    END IF;
    IF EXISTS (SELECT 1 FROM project.projects WHERE data_completion_status IS NOT NULL
               AND data_completion_status NOT IN ('COMPLETE','INCOMPLETE')) THEN
        RAISE EXCEPTION 'Invalid project completion statuses exist. Reconcile them before applying V28.';
    END IF;
END $$;

-- V2's broad status-constraint replacement also removed the completion-status check.
ALTER TABLE project.projects ADD CONSTRAINT chk_projects_completion_status
    CHECK (data_completion_status IS NULL OR data_completion_status IN ('COMPLETE','INCOMPLETE'));

ALTER TABLE project.file_types ADD CONSTRAINT uk_file_types_id_category UNIQUE(id, category_id);
ALTER TABLE project.files ADD CONSTRAINT chk_files_type_requires_category
    CHECK (type_id IS NULL OR category_id IS NOT NULL);
ALTER TABLE project.files ADD CONSTRAINT fk_files_type_category
    FOREIGN KEY(type_id, category_id) REFERENCES project.file_types(id, category_id);
ALTER TABLE import.package_projects ADD CONSTRAINT uk_package_projects_source_identity
    UNIQUE(package_id, local_project_uuid);
ALTER TABLE import.migration_settings ADD CONSTRAINT chk_migration_settings_singleton CHECK(singleton);

-- Old installations whose text actor columns were replaced by V2 need their FKs restored.
DO $$
DECLARE item RECORD;
BEGIN
    FOR item IN SELECT * FROM (VALUES
        ('project','projects','created_by','fk_projects_created_by_user'),
        ('project','projects','approved_by','fk_projects_approved_by_user'),
        ('import','branch_packages','received_by','fk_packages_received_by_user')
    ) AS needed(schema_name, table_name, column_name, constraint_name)
    LOOP
        IF NOT EXISTS (
            SELECT 1 FROM pg_constraint c JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=ANY(c.conkey)
            WHERE c.contype='f' AND c.conrelid=format('%I.%I',item.schema_name,item.table_name)::regclass
              AND c.confrelid='core.users'::regclass AND a.attname=item.column_name
        ) THEN
            EXECUTE format('ALTER TABLE %I.%I ADD CONSTRAINT %I FOREIGN KEY (%I) REFERENCES core.users(id)',
                item.schema_name,item.table_name,item.constraint_name,item.column_name);
        END IF;
    END LOOP;
END $$;
