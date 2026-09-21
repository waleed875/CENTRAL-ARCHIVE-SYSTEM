-- Central Platform MVP Schema - PostgreSQL
-- Files are stored in MinIO. This schema stores metadata and object keys only.
-- Schemas: core, project, import, audit

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- =============================================================================
-- SCHEMA: core
-- Management: Organizations, Users, Roles, Confidentiality Levels
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS core;

CREATE TABLE core.organizations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_id UUID REFERENCES core.organizations(id),
    name VARCHAR(200) NOT NULL,
    code VARCHAR(60) NOT NULL UNIQUE,
    type VARCHAR(30) NOT NULL CHECK (type IN ('COMPANY','FACTORY','LINE','DEPARTMENT')),
    description TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE core.confidentiality_levels (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(60) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    level_order INTEGER NOT NULL UNIQUE CHECK (level_order > 0),
    description TEXT
);

CREATE TABLE core.users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(80) NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    full_name VARCHAR(200) NOT NULL,
    email VARCHAR(200) UNIQUE,
    organization_id UUID REFERENCES core.organizations(id),
    max_confidentiality_level_id UUID REFERENCES core.confidentiality_levels(id),
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED','LOCKED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ
);

CREATE TABLE core.roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(60) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    description TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE core.user_roles (
    user_id UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES core.roles(id),
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    assigned_by UUID REFERENCES core.users(id),
    PRIMARY KEY (user_id, role_id)
);

-- =============================================================================
-- SCHEMA: project
-- Management: Projects, Members, File Categories, File Types, Files
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS project;

CREATE TABLE project.project_types (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(60) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    description TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE project.projects (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_uid UUID NOT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no >= 1),
    previous_project_id UUID REFERENCES project.projects(id),
    is_latest_version BOOLEAN NOT NULL DEFAULT TRUE,
    organization_id UUID NOT NULL REFERENCES core.organizations(id),
    project_type_id UUID REFERENCES project.project_types(id),
    confidentiality_level_id UUID REFERENCES core.confidentiality_levels(id),
    project_number VARCHAR(80) NOT NULL,
    project_code VARCHAR(100),
    name VARCHAR(250) NOT NULL,
    year INTEGER NOT NULL CHECK (year >= 1900),
    executing_entity VARCHAR(200),
    supervisor_name VARCHAR(200),
    description TEXT,
    notes TEXT,
    table_of_contents TEXT,
    keywords TEXT,
    status VARCHAR(40) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','IMPORTED','UNDER_REVIEW','NEEDS_CORRECTION','REJECTED','APPROVED','ARCHIVED')),
    data_completion_status VARCHAR(40) CHECK (data_completion_status IS NULL OR data_completion_status IN ('COMPLETE','INCOMPLETE')),
    review_notes TEXT,
    source_type VARCHAR(40) NOT NULL DEFAULT 'MANUAL' CHECK (source_type IN ('MANUAL','BRANCH_PACKAGE','MIGRATION')),
    source_reference TEXT,
    created_by UUID REFERENCES core.users(id),
    approved_by UUID REFERENCES core.users(id),
    approved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ,
    UNIQUE (project_uid, version_no),
    UNIQUE (organization_id, project_number, year, version_no)
);

CREATE TABLE project.project_members (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES project.projects(id) ON DELETE CASCADE,
    person_name VARCHAR(200) NOT NULL,
    organization_name VARCHAR(200),
    role_name VARCHAR(120),
    notes TEXT
);

CREATE TABLE project.file_categories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(60) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    description TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE project.file_types (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id UUID NOT NULL REFERENCES project.file_categories(id),
    code VARCHAR(60) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    default_extension VARCHAR(20),
    description TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE project.files (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES project.projects(id) ON DELETE CASCADE,
    category_id UUID REFERENCES project.file_categories(id),
    type_id UUID REFERENCES project.file_types(id),
    confidentiality_level_id UUID REFERENCES core.confidentiality_levels(id),
    original_name VARCHAR(300) NOT NULL,
    stored_name VARCHAR(300) NOT NULL,
    bucket_name VARCHAR(120) NOT NULL,
    object_key TEXT NOT NULL,
    extension VARCHAR(20),
    mime_type VARCHAR(150),
    size_bytes BIGINT CHECK (size_bytes IS NULL OR size_bytes >= 0),
    checksum_sha256 VARCHAR(64),
    status VARCHAR(40) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DELETED','REPLACED')),
    uploaded_by UUID REFERENCES core.users(id),
    source_type VARCHAR(40) NOT NULL DEFAULT 'MANUAL' CHECK (source_type IN ('MANUAL','BRANCH_PACKAGE','MIGRATION')),
    source_reference TEXT,
    deleted_at TIMESTAMPTZ,
    deleted_by UUID REFERENCES core.users(id),
    delete_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =============================================================================
-- SCHEMA: import
-- Management: Branch Packages, Import Operations, Migration
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS import;

CREATE TABLE import.branch_packages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    package_uuid UUID NOT NULL UNIQUE,
    source_organization_id UUID REFERENCES core.organizations(id),
    source_terminal_uuid UUID NOT NULL,
    source_username VARCHAR(120),
    package_name VARCHAR(300) NOT NULL,
    package_hash VARCHAR(64) NOT NULL UNIQUE,
    manifest_hash VARCHAR(64) NOT NULL,
    schema_version VARCHAR(30) NOT NULL,
    projects_count INTEGER NOT NULL DEFAULT 0,
    files_count INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(40) NOT NULL DEFAULT 'RECEIVED' CHECK (status IN ('RECEIVED','VERIFIED','IMPORTED','FAILED','DUPLICATE')),
    received_by UUID REFERENCES core.users(id),
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    verified_at TIMESTAMPTZ,
    imported_at TIMESTAMPTZ,
    error_message TEXT,
    manifest_data TEXT,
    target_organization_id UUID REFERENCES core.organizations(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ
);

CREATE TABLE import.package_projects (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    package_id UUID NOT NULL REFERENCES import.branch_packages(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES project.projects(id),
    local_project_uuid UUID NOT NULL,
    source_project_number VARCHAR(80),
    project_name VARCHAR(250),
    project_year INTEGER,
    import_status VARCHAR(40) NOT NULL DEFAULT 'IMPORTED' CHECK (import_status IN ('IMPORTED','SKIPPED','FAILED','DUPLICATE')),
    import_error TEXT,
    files_count INTEGER NOT NULL DEFAULT 0,
    files_imported INTEGER NOT NULL DEFAULT 0,
    files_failed INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (package_id, project_id)
);

CREATE TABLE import.import_operations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    package_id UUID NOT NULL REFERENCES import.branch_packages(id),
    imported_by UUID NOT NULL REFERENCES core.users(id),
    status VARCHAR(40) NOT NULL CHECK (status IN ('STARTED','SUCCESS','FAILED','PARTIAL')),
    error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE TABLE import.export_packages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    package_uuid UUID NOT NULL UNIQUE,
    package_name VARCHAR(300) NOT NULL,
    schema_version VARCHAR(30) NOT NULL,
    source_organization_id UUID REFERENCES core.organizations(id),
    source_username VARCHAR(120),
    projects_count INTEGER NOT NULL DEFAULT 0,
    files_count INTEGER NOT NULL DEFAULT 0,
    package_hash VARCHAR(64),
    manifest_hash VARCHAR(64),
    package_object_key TEXT,
    manifest_data TEXT,
    status VARCHAR(40) NOT NULL DEFAULT 'CREATED' CHECK (status IN ('CREATED','EXPORTED','FAILED')),
    exported_by UUID REFERENCES core.users(id),
    exported_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE import.migration_sources (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(200) NOT NULL,
    source_type VARCHAR(60),
    database_name VARCHAR(200),
    office_name VARCHAR(200),
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE import.migration_batches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    migration_source_id UUID NOT NULL REFERENCES import.migration_sources(id),
    batch_number VARCHAR(80) NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    status VARCHAR(40) NOT NULL DEFAULT 'PLANNED' CHECK (status IN ('PLANNED','RUNNING','SUCCESS','FAILED','PARTIAL')),
    projects_count INTEGER NOT NULL DEFAULT 0,
    files_count INTEGER NOT NULL DEFAULT 0,
    notes TEXT
);

-- =============================================================================
-- SCHEMA: audit
-- Management: Audit Logs
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS audit;

CREATE TABLE audit.audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES core.users(id),
    action VARCHAR(100) NOT NULL,
    entity_type VARCHAR(100),
    entity_id UUID,
    description TEXT,
    old_values JSONB,
    new_values JSONB,
    ip_address INET,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =============================================================================
-- INDEXES
-- =============================================================================

-- core schema indexes
CREATE INDEX idx_org_parent ON core.organizations(parent_id);
CREATE INDEX idx_users_org ON core.users(organization_id);

-- project schema indexes
CREATE INDEX idx_projects_org_year ON project.projects(organization_id, year);
CREATE INDEX idx_projects_uid_latest ON project.projects(project_uid, is_latest_version);
CREATE INDEX idx_projects_status ON project.projects(status);
CREATE INDEX idx_files_project ON project.files(project_id);
CREATE INDEX idx_files_checksum ON project.files(checksum_sha256);

-- import schema indexes
CREATE INDEX idx_branch_packages_status ON import.branch_packages(status);

-- audit schema indexes
CREATE INDEX idx_audit_entity ON audit.audit_logs(entity_type, entity_id);
CREATE INDEX idx_audit_user_time ON audit.audit_logs(user_id, created_at DESC);
