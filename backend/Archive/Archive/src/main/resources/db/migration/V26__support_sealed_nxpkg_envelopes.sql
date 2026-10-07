-- Support encrypted omnivault/nxpkg envelopes without decrypting payload.enc.
-- Legacy plain .pkg packages keep their existing VERIFIED -> IMPORTED workflow.

ALTER TABLE import.branch_packages
    ALTER COLUMN manifest_hash DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS package_format VARCHAR(80) NOT NULL DEFAULT 'central-archive/pkg',
    ADD COLUMN IF NOT EXISTS package_version INTEGER,
    ADD COLUMN IF NOT EXISTS encrypted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS header_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS payload_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS signature_valid BOOLEAN,
    ADD COLUMN IF NOT EXISTS terminal_fingerprint VARCHAR(64),
    ADD COLUMN IF NOT EXISTS header_data TEXT,
    ADD COLUMN IF NOT EXISTS payload_size_bytes BIGINT;

ALTER TABLE import.branch_packages
    DROP CONSTRAINT IF EXISTS branch_packages_status_check;

ALTER TABLE import.branch_packages
    ADD CONSTRAINT branch_packages_status_check
    CHECK (status IN (
        'RECEIVED',
        'SEALED_VERIFIED',
        'VERIFIED',
        'IMPORTED',
        'FAILED',
        'DUPLICATE'
    ));

ALTER TABLE import.branch_packages
    ADD CONSTRAINT branch_packages_payload_size_nonnegative
    CHECK (payload_size_bytes IS NULL OR payload_size_bytes >= 0);
