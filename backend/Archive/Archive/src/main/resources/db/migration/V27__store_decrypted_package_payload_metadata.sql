-- Persist integrity metadata for payloads decrypted during encrypted package upload.
ALTER TABLE import.branch_packages
    ADD COLUMN IF NOT EXISTS decrypted_payload_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS decrypted_payload_size_bytes BIGINT;

ALTER TABLE import.branch_packages
    DROP CONSTRAINT IF EXISTS branch_packages_status_check;

ALTER TABLE import.branch_packages
    ADD CONSTRAINT branch_packages_status_check
    CHECK (status IN (
        'RECEIVED',
        'SEALED_VERIFIED',
        'DECRYPTED',
        'VERIFIED',
        'IMPORTED',
        'FAILED',
        'DUPLICATE'
    ));

ALTER TABLE import.branch_packages
    DROP CONSTRAINT IF EXISTS branch_packages_decrypted_payload_size_nonnegative;

ALTER TABLE import.branch_packages
    ADD CONSTRAINT branch_packages_decrypted_payload_size_nonnegative
    CHECK (decrypted_payload_size_bytes IS NULL OR decrypted_payload_size_bytes >= 0);
