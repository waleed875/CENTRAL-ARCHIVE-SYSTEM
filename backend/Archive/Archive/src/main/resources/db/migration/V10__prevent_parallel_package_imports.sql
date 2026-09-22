-- Only one active import operation may exist for a package at a time.
CREATE UNIQUE INDEX IF NOT EXISTS uk_import_operations_one_started_per_package
    ON import.import_operations(package_id)
    WHERE status = 'STARTED';
