-- Local Terminal MVP Schema - SQLite
-- This database is for data entry and encrypted package export only.
PRAGMA foreign_keys = ON;

CREATE TABLE terminal_settings (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    terminal_uuid TEXT NOT NULL UNIQUE,
    assigned_user_name TEXT,
    assigned_organization_id TEXT,
    schema_version TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE local_users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    full_name TEXT NOT NULL,
    is_active INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE local_organizations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    parent_id INTEGER REFERENCES local_organizations(id),
    central_organization_id TEXT,
    name TEXT NOT NULL,
    code TEXT,
    type TEXT NOT NULL CHECK (type IN ('COMPANY','FACTORY','LINE','DEPARTMENT')),
    is_active INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE local_project_types (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    central_id TEXT,
    code TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    is_active INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE local_confidentiality_levels (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    central_id TEXT,
    code TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    level_order INTEGER NOT NULL
);

CREATE TABLE local_projects (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    local_uuid TEXT NOT NULL UNIQUE,
    project_uid TEXT NOT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no >= 1),
    previous_local_project_id INTEGER REFERENCES local_projects(id),
    is_locked_after_export INTEGER NOT NULL DEFAULT 0,
    organization_id INTEGER NOT NULL REFERENCES local_organizations(id),
    project_type_id INTEGER REFERENCES local_project_types(id),
    confidentiality_level_id INTEGER REFERENCES local_confidentiality_levels(id),
    project_number TEXT NOT NULL,
    project_code TEXT,
    name TEXT NOT NULL,
    year INTEGER NOT NULL,
    executing_entity TEXT,
    supervisor_name TEXT,
    description TEXT,
    notes TEXT,
    table_of_contents TEXT,
    keywords TEXT,
    status TEXT NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','READY_FOR_EXPORT','EXPORTED')),
    created_by INTEGER REFERENCES local_users(id),
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT,
    UNIQUE (project_uid, version_no)
);

CREATE TABLE local_project_members (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id INTEGER NOT NULL REFERENCES local_projects(id) ON DELETE CASCADE,
    person_name TEXT NOT NULL,
    organization_name TEXT,
    role_name TEXT,
    notes TEXT
);

CREATE TABLE local_file_categories (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    central_id TEXT,
    code TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    is_active INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE local_file_types (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    category_id INTEGER NOT NULL REFERENCES local_file_categories(id),
    central_id TEXT,
    code TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    default_extension TEXT,
    is_active INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE local_files (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    local_uuid TEXT NOT NULL UNIQUE,
    project_id INTEGER NOT NULL REFERENCES local_projects(id) ON DELETE CASCADE,
    category_id INTEGER REFERENCES local_file_categories(id),
    type_id INTEGER REFERENCES local_file_types(id),
    confidentiality_level_id INTEGER REFERENCES local_confidentiality_levels(id),
    original_name TEXT NOT NULL,
    local_path TEXT NOT NULL,
    extension TEXT,
    mime_type TEXT,
    size_bytes INTEGER,
    checksum_sha256 TEXT,
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REMOVED')),
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE export_packages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    package_uuid TEXT NOT NULL UNIQUE,
    package_name TEXT NOT NULL,
    manifest_json TEXT NOT NULL,
    package_hash TEXT UNIQUE,
    schema_version TEXT NOT NULL,
    file_path TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'CREATED' CHECK (status IN ('CREATED','EXPORTED','FAILED')),
    created_by INTEGER REFERENCES local_users(id),
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE export_package_projects (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    package_id INTEGER NOT NULL REFERENCES export_packages(id) ON DELETE CASCADE,
    local_project_id INTEGER NOT NULL REFERENCES local_projects(id),
    UNIQUE (package_id, local_project_id)
);

CREATE INDEX idx_local_org_parent ON local_organizations(parent_id);
CREATE INDEX idx_local_projects_org ON local_projects(organization_id);
CREATE INDEX idx_local_projects_uid ON local_projects(project_uid, version_no);
CREATE INDEX idx_local_files_project ON local_files(project_id);
CREATE INDEX idx_local_files_checksum ON local_files(checksum_sha256);
CREATE INDEX idx_export_package_status ON export_packages(status);
