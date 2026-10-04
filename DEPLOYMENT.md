# Central Archive System - Deployment and Recovery Guide

This guide describes the production deployment baseline for the central backend.

> **Installing on a server?** Use [`deploy/linux/README.md`](deploy/linux/README.md). It has the
> systemd units, the TLS gateway, the install and verification scripts, and the
> troubleshooting table. The sections below are the general baseline and stay
> authoritative for backups, restore, and audit retention.

## 1. Production architecture

The backend requires:

- Java 21
- PostgreSQL 17 or a compatible supported PostgreSQL release
- MinIO or another compatible S3 endpoint supported by the current MinIO client
- A reverse proxy / TLS termination layer
- Persistent backups for both PostgreSQL and MinIO

The application database schema is managed by Flyway. Hibernate runs with `ddl-auto=validate` and must never be used to mutate the production schema.

## 2. Required production environment

Activate the production profile:

```text
SPRING_PROFILES_ACTIVE=prod
```

Required values:

```text
DB_URL=jdbc:postgresql://db-host:5432/central_archive
DB_USERNAME=...
DB_PASSWORD=...

MINIO_ENDPOINT=https://minio.example.internal
MINIO_ACCESS_KEY=...
MINIO_SECRET_KEY=...
MINIO_BUCKET=archive-files

JWT_SECRET=<base64 random secret, at least 32 bytes before Base64 encoding>
CORS_ALLOWED_ORIGINS=https://archive.example.com
ARCHIVE_INSTANCE_UUID=<stable UUID for this central installation>
```

`SETTINGS_ENCRYPTION_KEY` is also required and has no default. It encrypts the
confidentiality code held in `import.migration_settings` and every per-office legacy
database password on `import.migration_sources`.

> **This value is irreversible.** The stored ciphertext is decrypted with a key derived
> from this string. Changing it makes the application unable to read existing values,
> and there is no rotation path and no recovery. Back it up with the same care as the
> database itself, and never regenerate it during an upgrade.

`CORS_ALLOWED_ORIGINS` is rarely load-bearing in the shipped topology. The browser calls
`/api/gateway` on the frontend origin, and the Next.js server forwards to the API
server-side, so the API is never called cross-origin. Set it to the exact public origin
anyway, because the application refuses to start without it, and never to `*`.

Optional operational settings:

```text
SERVER_PORT=8080
SERVER_ADDRESS=127.0.0.1
JWT_EXPIRATION_MS=3600000
JWT_REFRESH_EXPIRATION_MS=604800000
PACKAGE_SUPPORTED_SCHEMA_VERSIONS=1.0
MAX_PACKAGE_UPLOAD_SIZE=20GB
MAX_PACKAGE_REQUEST_SIZE=20GB
SWAGGER_ENABLED=false
BOOTSTRAP_ADMIN_ENABLED=false
FLYWAY_BASELINE_ON_MIGRATE=true
FORWARD_HEADERS_STRATEGY=none
```

`SERVER_ADDRESS` should stay on loopback when a reverse proxy runs on the same host.
Exposing the application port publicly puts the Spring Security stack on the network
without the reverse proxy in front of it.

Only enable forwarded-header handling when traffic reaches the application exclusively through a trusted reverse proxy that strips untrusted client-supplied `Forwarded` and `X-Forwarded-*` headers.

## 3. First deployment

1. Provision PostgreSQL and create an empty `central_archive` database.
2. Provision MinIO and create credentials dedicated to this application.
3. Configure the production environment variables.
4. Start the backend.
5. Flyway applies `V1` and all later migrations before Hibernate validates the schema.
6. Verify the health endpoint.
7. Create/provision the first administrator using a controlled bootstrap window if required, then disable bootstrap again.

Do not pre-load `Central_Platform_ERD/central_platform_mvp_postgresql.sql` into a new production database. The Flyway files under `backend/Archive/Archive/src/main/resources/db/migration` are the executable schema history.

## 4. Upgrading an existing pre-Flyway database

Before the first Flyway-enabled deployment:

1. Stop all application writers.
2. Back up PostgreSQL and MinIO.
3. Verify the backups.
4. Deploy the Flyway-enabled release with `FLYWAY_BASELINE_ON_MIGRATE=true`.
5. Flyway records the existing non-empty schema as the V1 baseline and applies compatibility migrations from V2 onward.
6. Hibernate then validates the resulting schema.

Do not manually mark a failed migration as successful. Fix the migration problem, restore if necessary, and rerun through the controlled deployment process.

## 5. PostgreSQL backup

Example custom-format backup:

```bash
pg_dump \
  --format=custom \
  --no-owner \
  --no-privileges \
  --host="$PGHOST" \
  --port="${PGPORT:-5432}" \
  --username="$PGUSER" \
  --file="central_archive_$(date +%Y%m%d_%H%M%S).dump" \
  central_archive
```

Also record the application release/commit and current Flyway version at backup time.

A backup is not considered valid until a restore test succeeds.

## 6. PostgreSQL restore

Restore into a new empty database first:

```bash
createdb -h "$PGHOST" -p "${PGPORT:-5432}" -U "$PGUSER" central_archive_restore_test

pg_restore \
  --clean \
  --if-exists \
  --no-owner \
  --no-privileges \
  --host="$PGHOST" \
  --port="${PGPORT:-5432}" \
  --username="$PGUSER" \
  --dbname=central_archive_restore_test \
  central_archive_YYYYMMDD_HHMMSS.dump
```

Run the backend against the restored database with write traffic disabled and verify:

- Flyway history is consistent.
- Hibernate schema validation succeeds.
- representative projects can be queried.
- package provenance and audit history are present.

## 7. MinIO backup

PostgreSQL contains file metadata; MinIO contains the actual archive objects. Both must be backed up from the same operational window.

Use one of these strategies:

- bucket replication to another MinIO/S3 target,
- immutable object-storage snapshots provided by the infrastructure,
- an approved MinIO client mirror/replication procedure.

The backup must include the main archive bucket, original imported package objects, exported package objects, and object version history if bucket versioning is enabled.

Do not rely on PostgreSQL backup alone.

## 8. Recovery consistency rule

A project/file row is useful only if its referenced MinIO object exists. Recovery should therefore restore PostgreSQL and MinIO to a compatible point in time.

After recovery, verify that each sampled `project.files.bucket_name + object_key` resolves to an existing MinIO object, and separately verify preserved package objects referenced by import/export records.

## 9. Health checks

Production exposes limited Actuator endpoints:

```text
/actuator/health
/actuator/info
```

Recommended probes:

- liveness: `/actuator/health/liveness`
- readiness: `/actuator/health/readiness`

## 10. Audit retention and immutability

The application audit table is append-only. Flyway migration V8 installs a
PostgreSQL trigger that rejects normal `UPDATE` and `DELETE` operations on
`audit.audit_logs`.

Choose the legal/organizational retention period before production rollout.
A seven-year retention window is a reasonable operational default only when it
matches the organization's policy; it is not hard-coded by the application.

Retention deletion must be executed as a controlled database maintenance
operation after the relevant PostgreSQL backup has been verified. Example for a
seven-year policy:

```sql
BEGIN;
SET LOCAL app.audit_retention_purge = 'on';

DELETE FROM audit.audit_logs
WHERE created_at < now() - interval '7 years';

COMMIT;
```

Do not expose the retention-purge setting through the application API. Record
the maintenance ticket/change identifier, purge boundary, row count, operator,
and backup reference outside the table being purged.

## 11. Controlled legacy migration window

Legacy migration is a maintenance operation against a separately restored
PostgreSQL staging database. It is disabled by default.

### Per-office sources

Each office is a separate migration source, and each source carries its own connection.
`office_name` identifies the office, is unique across all sources, and becomes part of
every migrated project UID and source reference.

A source either carries a complete connection of its own — `host`, `port`,
`databaseName`, `username` and an AES-GCM-encrypted `password` — or it carries none of
them and falls back to the `legacy.datasource.*` environment default. A half-filled
connection is rejected at save time rather than stored, because a source with a host but
no username would look configured and then build an unusable JDBC URL at migration time,
far from the form that caused it.

`office_name` cannot be changed once the source has migrated anything, because it is
part of the identity of every row already migrated under it. Correct a placeholder
office name before the first run, and create a new source rather than renaming one that
has already run.

The stored password is never returned by any API response. Rotating a legacy database
password means re-entering it in the source editor; a blank field keeps the stored one.

### Before enabling migration:

1. restore and validate the legacy backup in an isolated staging database;
2. verify PostgreSQL and MinIO backups for the central archive;
3. create the migration source for the office and store its connection credentials, or
   set `LEGACY_DB_URL`, `LEGACY_DB_USERNAME` and `LEGACY_DB_PASSWORD` for a source that
   should use the environment default;
4. keep `LEGACY_MIGRATION_SCHEDULE_ENABLED=false` unless automatic scheduling
   is explicitly required;
5. set `LEGACY_MIGRATION_ENABLED=true` only for the controlled migration window.

A run request carries no connection string. The batch opens the connection stored on its
own source, and the API rejects a non-blank `migration_config.connection_string` rather
than ignoring it, so nobody can assume a URL they supplied was used. File-based
migration adapters, per-run type mappings, and per-run confidentiality overrides are
also not part of the current MVP and are rejected.

Legacy file bodies are decoded from Base64 to a temporary spool file one item
at a time, content-inspected, hashed, uploaded to MinIO, and then persisted.
Ensure the host has enough temporary disk for the largest single legacy file.

### Reading the outcome

A batch ends in `SUCCESS`, `FAILED`, or `PARTIAL`. `PARTIAL` is not a cosmetic state:
it means some rows could not be migrated and the count is short. A legacy row that
violates a unique constraint is recorded as a `FAILED` tracking item and the batch
continues, rather than the whole transaction being rolled back.

Two failure classes are worth distinguishing:

- **A duplicate project number inside one office.** The central archive treats one
  project number per organisation per year as one project, so extra legacy rows carrying
  the same number are recorded `FAILED` and any files belonging to them are not
  migrated. This is legacy data that needs cleaning, not a migration bug. Decide
  deliberately whether to merge the files onto the surviving project or to disambiguate
  the numbers, because the second option changes project identity.
- **A project that already exists under the same number from an earlier run.** These are
  skipped as already migrated, which is the intended idempotent behaviour.

A file row is only useful if its MinIO object exists, so a `PARTIAL` batch leaves
metadata short of objects. Resolve the `FAILED` items and re-run before closing the
window; note that a tracking row already recorded as `FAILED` is not automatically
retried, so a corrected source may still need its failed rows cleared.

### After the migration window:

1. confirm all batches are in a terminal state;
2. review FAILED/PARTIAL items and migration counts;
3. set `LEGACY_MIGRATION_ENABLED=false`;
4. keep `LEGACY_MIGRATION_SCHEDULE_ENABLED=false`;
5. remove or rotate legacy staging credentials if they are no longer needed.


## 12. Release checklist

- GitHub Actions backend CI is green.
- Flyway migrations pass against clean PostgreSQL.
- Hibernate `validate` passes.
- authentication/security tests pass.
- package verification tests pass.
- no development secrets are used.
- `SETTINGS_ENCRYPTION_KEY` is present and is the value that encrypted the existing data.
- Swagger is disabled unless explicitly required.
- bootstrap admin is disabled.
- the application port is bound to loopback behind the reverse proxy.
- PostgreSQL backup succeeds.
- MinIO backup/replication is healthy.
- restore procedure has been tested recently.
- legacy migration is disabled unless an approved migration window is active.
- the legacy migration scheduler is disabled unless explicitly required.


## 13. Rollback

If a release includes only backward-compatible migrations, the previous application may be redeployed if its JPA model still validates against the newer schema.

If a migration is not backward compatible:

1. stop write traffic,
2. restore PostgreSQL and MinIO from the pre-deployment backups,
3. deploy the previous application release,
4. validate health and representative archive access before reopening traffic.

Never attempt an ad-hoc reverse migration on the production archive without a tested rollback script.
