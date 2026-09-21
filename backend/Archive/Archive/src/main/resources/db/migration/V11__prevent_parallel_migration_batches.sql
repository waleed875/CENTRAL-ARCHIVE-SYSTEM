-- The legacy migration runner is single-execution. Prevent a second batch from
-- entering RUNNING and becoming stranded while another batch is active.
CREATE UNIQUE INDEX IF NOT EXISTS uk_migration_batches_one_running
    ON import.migration_batches ((status))
    WHERE status = 'RUNNING';
