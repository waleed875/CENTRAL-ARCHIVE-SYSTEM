# Tuning legacy database migration throughput safely

The legacy source stores Base64 in `public.tbl_files.filebinary`. Its values
can be very large. Fetching an entire BYTEA through pgJDBC may materialize
the whole encoded file on Java's heap, so migration reads bounded PostgreSQL
`substring` chunks and decodes to a temporary spool file before uploading
to MinIO. A slower run after PR #44 is expected: it lowered file workers
from 4 to 1 by default to mitigate OutOfMemoryError.

## Tunables (Docker Compose)

```dotenv
# Recommended first experiment, not a hardcoded production default.
LEGACY_MIGRATION_FILE_WORKERS=2
# 2 MiB (the new default); range 256 KiB..4 MiB
LEGACY_MIGRATION_BYTEA_CHUNK_BYTES=2097152
```

Stop the migration cleanly in the admin UI before recreating the Backend.
Then apply the environment variables through Compose and resume the batch
after verifying that the existing file tracking records are complete.
Do not use `docker compose down -v`.

The concurrent file readers and spooled files have different resource costs:
worker count primarily controls parallel PostgreSQL network reads, Base64
decode tasks and MinIO writes; chunk size controls each DB query result.
A 100 MiB Base64 value requires about 50 queries at 2 MiB, compared with
200 queries at 512 KiB. PostgreSQL may need to decompress a large TOASTed
value repeatedly for each substring query, so throughput still depends on
the legacy database, network and available CPU.

## Measurement

1. Start at 1 worker, 2 MiB, measure completed files/minute, MiB/second,
   maximum backend heap, PostgreSQL CPU, disk/temp free space, MinIO uploads,
   and errors for a representative mix of sizes.
2. Retry at 2 workers **only if** 1 worker is stable, then compare throughput
   and memory. If there are no OutOfMemoryError events, consider 3 workers,
   but do not increase aggressively. `MigrationRunner` clamps workers to 1..4.
3. If network round-trips are dominant, consider 4 MiB chunks (4194304)
   and monitor heap. If source PostgreSQL is overloaded or Backend memory
   rises, reduce workers or chunk size.
4. Never remove checksumming, quarantine checks, MinIO verification or
   source-project linking to gain speed. Completed migration rows are reused
   on retry instead of duplicating file records.

A successful CI run verifies correctness, but it does not benchmark
throughput on the actual legacy server. Compare rates before and after
the tuning change using the same subset of files and retain log evidence.
