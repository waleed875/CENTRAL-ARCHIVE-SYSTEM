-- Central Platform MVP Schema - PostgreSQL
-- Files are stored in MinIO. This schema stores metadata and object keys only.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE organizations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_id UUID REFERENCES organizations(id),
    name VARCHAR(200) NOT NULL,
    code VARCHAR(60) NOT NULL UNIQUE,
    type VARCHAR(30) NOT NULL CHECK (type IN ('COMPANY','FACTORY','LINE','DEPARTMENT')),
    description TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE confidentiality_levels (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(60) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    level_order INTEGER NOT NULL UNIQUE CHECK (level_order > 0),
    description TEXT
);

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(80) NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    full_name VARCHAR(200) NOT NULL,
    email VARCHAR(200) UNIQUE,
    organization_id UUID REFERENCES organizations(id),
    max_confidentiality_level_id UUID REFERENCES confidentiality_levels(id),
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED','LOCKED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ
);

CREATE TABLE roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(60) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    description TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles(id),
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    assigned_by UUID REFERENCES users(id),
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE project_types (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(60) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    description TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE projects (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_uid UUID NOT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no >= 1),
    previous_project_id UUID REFERENCES projects(id),
    is_latest_version BOOLEAN NOT NULL DEFAULT TRUE,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    project_type_id UUID REFERENCES project_types(id),
    confidentiality_level_id UUID REFERENCES confidentiality_levels(id),
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
    status VARCHAR(40) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','IMPORTED','UNDER_REVIEW','NEEDS_CORRECTION','APPROVED','ARCHIVED')),
    data_completion_status VARCHAR(40) CHECK (data_completion_status IS NULL OR data_completion_status IN ('COMPLETE','INCOMPLETE')),
    review_notes TEXT,
    source_type VARCHAR(40) NOT NULL DEFAULT 'MANUAL' CHECK (source_type IN ('MANUAL','BRANCH_PACKAGE','MIGRATION')),
    source_reference TEXT,
    created_by UUID REFERENCES users(id),
    approved_by UUID REFERENCES users(id),
    approved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ,
    UNIQUE (project_uid, version_no),
    UNIQUE (organization_id, project_number, year, version_no)
);

CREATE TABLE project_members (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    person_name VARCHAR(200) NOT NULL,
    organization_name VARCHAR(200),
    role_name VARCHAR(120),
    notes TEXT
);

CREATE TABLE file_categories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(60) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    description TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE file_types (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id UUID NOT NULL REFERENCES file_categories(id),
    code VARCHAR(60) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    default_extension VARCHAR(20),
    description TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE files (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    category_id UUID REFERENCES file_categories(id),
    type_id UUID REFERENCES file_types(id),
    confidentiality_level_id UUID REFERENCES confidentiality_levels(id),
    original_name VARCHAR(300) NOT NULL,
    stored_name VARCHAR(300) NOT NULL,
    bucket_name VARCHAR(120) NOT NULL,
    object_key TEXT NOT NULL,
    extension VARCHAR(20),
    mime_type VARCHAR(150),
    size_bytes BIGINT CHECK (size_bytes IS NULL OR size_bytes >= 0),
    checksum_sha256 CHAR(64),
    status VARCHAR(40) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DELETED','REPLACED')),
    uploaded_by UUID REFERENCES users(id),
    source_type VARCHAR(40) NOT NULL DEFAULT 'MANUAL' CHECK (source_type IN ('MANUAL','BRANCH_PACKAGE','MIGRATION')),
    source_reference TEXT,
    deleted_at TIMESTAMPTZ,
    deleted_by UUID REFERENCES users(id),
    delete_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE branch_packages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    package_uuid UUID NOT NULL UNIQUE,
    source_organization_id UUID REFERENCES organizations(id),
    source_terminal_uuid UUID NOT NULL,
    source_username VARCHAR(120),
    package_name VARCHAR(300) NOT NULL,
    package_hash CHAR(64) NOT NULL UNIQUE,
    manifest_hash CHAR(64) NOT NULL,
    schema_version VARCHAR(30) NOT NULL,
    projects_count INTEGER NOT NULL DEFAULT 0,
    files_count INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(40) NOT NULL DEFAULT 'RECEIVED' CHECK (status IN ('RECEIVED','VERIFIED','IMPORTED','FAILED','DUPLICATE')),
    received_by UUID REFERENCES users(id),
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE package_projects (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    package_id UUID NOT NULL REFERENCES branch_packages(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES projects(id),
    local_project_uuid UUID NOT NULL,
    import_status VARCHAR(40) NOT NULL DEFAULT 'IMPORTED' CHECK (import_status IN ('IMPORTED','SKIPPED','FAILED','DUPLICATE')),
    UNIQUE (package_id, project_id)
);

CREATE TABLE import_operations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    package_id UUID NOT NULL REFERENCES branch_packages(id),
    imported_by UUID NOT NULL REFERENCES users(id),
    status VARCHAR(40) NOT NULL CHECK (status IN ('STARTED','SUCCESS','FAILED','PARTIAL')),
    error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE TABLE migration_sources (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(200) NOT NULL,
    source_type VARCHAR(60),
    database_name VARCHAR(200),
    office_name VARCHAR(200),
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE migration_batches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    migration_source_id UUID NOT NULL REFERENCES migration_sources(id),
    batch_number VARCHAR(80) NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    status VARCHAR(40) NOT NULL DEFAULT 'PLANNED' CHECK (status IN ('PLANNED','RUNNING','SUCCESS','FAILED','PARTIAL')),
    projects_count INTEGER NOT NULL DEFAULT 0,
    files_count INTEGER NOT NULL DEFAULT 0,
    notes TEXT
);

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id),
    action VARCHAR(100) NOT NULL,
    entity_type VARCHAR(100),
    entity_id UUID,
    description TEXT,
    old_values JSONB,
    new_values JSONB,
    ip_address INET,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_org_parent ON organizations(parent_id);
CREATE INDEX idx_users_org ON users(organization_id);
CREATE INDEX idx_projects_org_year ON projects(organization_id, year);
CREATE INDEX idx_projects_uid_latest ON projects(project_uid, is_latest_version);
CREATE INDEX idx_projects_status ON projects(status);
CREATE INDEX idx_files_project ON files(project_id);
CREATE INDEX idx_files_checksum ON files(checksum_sha256);
CREATE INDEX idx_branch_packages_status ON branch_packages(status);
CREATE INDEX idx_audit_entity ON audit_logs(entity_type, entity_id);
CREATE INDEX idx_audit_user_time ON audit_logs(user_id, created_at DESC);
