# Legacy migration: direct JDBC read, four workers

Restores the historical pre-17-November file read behavior: query
`SELECT filebinary FROM public.tbl_files WHERE idfile = ?`, then
`ResultSet.getBinaryStream(1)`. No PostgreSQL `substring(filebinary...)`
queries or configurable BYTEA chunk sizes are used in the file-transfer path.

The project-ID and file-metadata pagination, source-scoped retry, checksum,
quarantine, MinIO upload, and transaction tracking are preserved.

Default transfer concurrency is 4 workers (configurable with
`LEGACY_MIGRATION_FILE_WORKERS`, clamped to 1..4 by MigrationRunner).

**High memory / crash risk:** pgJDBC can materialize complete BYTEA columns
on the JVM heap, and four file transfers can overlap. Monitor Java heap,
container RSS, PostgreSQL load, MinIO transfer, and free spool disk. Run on
a representative isolated staging copy first. A CI pass does not prove
production memory safety. Stop migration safely and lower workers to 1
if memory pressure rises.

Do not change migration source identities or remove checksum/quarantine.
The existing legacy data and MinIO buckets are not automatically modified
by this source-code change.
