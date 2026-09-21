# Central Archive System - Deployment and Recovery Guide

This guide describes the production deployment baseline for the central backend.

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

Optional operational settings:

```text
SERVER_PORT=8080
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

## 10. Release checklist

- GitHub Actions backend CI is green.
- Flyway migrations pass against clean PostgreSQL.
- Hibernate `validate` passes.
- authentication/security tests pass.
- package verification tests pass.
- no development secrets are used.
- Swagger is disabled unless explicitly required.
- bootstrap admin is disabled.
- PostgreSQL backup succeeds.
- MinIO backup/replication is healthy.
- restore procedure has been tested recently.

## 11. Rollback

If a release includes only backward-compatible migrations, the previous application may be redeployed if its JPA model still validates against the newer schema.

If a migration is not backward compatible:

1. stop write traffic,
2. restore PostgreSQL and MinIO from the pre-deployment backups,
3. deploy the previous application release,
4. validate health and representative archive access before reopening traffic.

Never attempt an ad-hoc reverse migration on the production archive without a tested rollback script.
