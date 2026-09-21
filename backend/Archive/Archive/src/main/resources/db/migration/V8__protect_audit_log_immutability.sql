-- Make audit logs append-only for normal application/database activity.
-- Controlled retention purge is allowed only when the maintenance transaction
-- explicitly sets app.audit_retention_purge = 'on'.

CREATE OR REPLACE FUNCTION audit.enforce_audit_log_immutability()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'audit.audit_logs is append-only; UPDATE is not allowed';
    END IF;

    IF TG_OP = 'DELETE'
       AND current_setting('app.audit_retention_purge', true) IS DISTINCT FROM 'on' THEN
        RAISE EXCEPTION
            'audit.audit_logs DELETE requires controlled retention purge mode';
    END IF;

    RETURN OLD;
END;
$$;

DROP TRIGGER IF EXISTS trg_audit_logs_immutable ON audit.audit_logs;

CREATE TRIGGER trg_audit_logs_immutable
BEFORE UPDATE OR DELETE ON audit.audit_logs
FOR EACH ROW
EXECUTE FUNCTION audit.enforce_audit_log_immutability();
