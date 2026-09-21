-- Persist per-user JWT revocation/rotation cutoff.
ALTER TABLE core.users
    ADD COLUMN IF NOT EXISTS token_valid_after TIMESTAMPTZ;
