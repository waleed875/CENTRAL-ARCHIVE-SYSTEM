-- Normalize SHA-256 columns to VARCHAR(64) so Hibernate String mappings
-- validate consistently across PostgreSQL/JDBC versions.

ALTER TABLE project.files
    ALTER COLUMN checksum_sha256 TYPE VARCHAR(64)
    USING trim(checksum_sha256);

ALTER TABLE import.branch_packages
    ALTER COLUMN package_hash TYPE VARCHAR(64)
    USING trim(package_hash),
    ALTER COLUMN manifest_hash TYPE VARCHAR(64)
    USING trim(manifest_hash);

ALTER TABLE import.export_packages
    ALTER COLUMN package_hash TYPE VARCHAR(64)
    USING trim(package_hash),
    ALTER COLUMN manifest_hash TYPE VARCHAR(64)
    USING trim(manifest_hash);
