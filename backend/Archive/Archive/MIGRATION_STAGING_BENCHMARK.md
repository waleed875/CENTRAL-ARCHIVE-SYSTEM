# Legacy migration performance and memory benchmark (staging only)

The migration PR includes process-local throughput telemetry, while the historical direct-stream fallback trades fewer queries for significantly higher heap risk. **Do not merge or deploy based on a CI pass alone.** Compare representative staging runs and record both speed and peak memory before adjusting transfer settings.

## Prerequisites

1. Restore a production-like **sanitized** legacy database into isolated staging; preserve representative file sizes, extensions, counts, and the proportion of quarantined files.
2. Give the staging backend its own PostgreSQL and MinIO bucket. Take backups first, with enough disk for the full transfer and temporary spooling.
3. Start from a clean, equivalent target database/bucket for each baseline and candidate; a resumed migration skips DONE entries and is not comparable to a first run.
4. Run only one migration batch at a time. Stop the batch safely in the UI if memory, disk, or error rates indicate a problem. Never kill the process or delete orphan candidates as an optimization.
5. Use an admin access token stored in `MIGRATION_BENCHMARK_TOKEN`. **Do not paste it into command arguments, a CSV, issue, or Git commit.** Do not check in the CSV if environment names or timings are sensitive.

## Collect metrics

From a terminal that can access the staging backend:

```bash
export MIGRATION_BENCHMARK_TOKEN='your-short-lived-staging-token'
python3 backend/Archive/Archive/scripts/migration_benchmark.py \
  --url http://127.0.0.1:8080 \
  --batch-id 00000000-0000-0000-0000-000000000000 \
  --output run-baseline.csv \
  --interval 3 --duration 3600 \
  --docker-container archive-backend
unset MIGRATION_BENCHMARK_TOKEN
```

Use a real staging batch UUID in place of the example and omit `--docker-container` if the backend is not a local Docker container. This collector is **read-only**: it never starts, stops, resumes, or alters a migration. It polls the admin-only batch status and live-progress endpoints; the optional memory source is `docker stats` from the current host.

**How to interpret the CSV:** `files_per_minute` and `mib_per_second` are a rolling 60-second sample, not cumulative totals; `transferred_bytes` counts only completed files in the active application process. If a restart or load balancer serves a different instance, the live snapshot may be unavailable. Memory measurements are container observations, not exact JVM heap usage; add JVM/GC diagnostics when available. Avoid treating a blank memory field as zero.

## Controlled comparison

| Run | Code | BYTEA chunk | Workers | Data | Peak container memory | MiB/s during steady transfer | Files/min | Failed | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Baseline | current main | current main default | 1 | identical staging fixture | measure | measure | measure | measure | fresh target |
| Candidate A | PR #47 | current default | 1 | same fixture, fresh target | measure | measure | measure | measure | isolate pagination + observability |
| Candidate B | PR #45 + compatible changes | 2 MiB proposed default | 1 | same fixture, fresh target | measure | measure | measure | measure | validate memory budget |
| Candidate C | verified combined candidate | measured choice | 2 | same fixture, fresh target | measure | measure | measure | measure | only if A/B have margin |

Write down the CPU, JVM heap limit, MinIO latency, database latency, duration, total files/bytes, p95 file size, and disk space for each run. Keep file signatures, checksums, quarantine policy, and project associations unchanged. Verify every file remains retrievable or explicitly quarantined with appropriate metadata; check the reconciliation report.

## Release gates

- Backend, Frontend and Security CI must pass **on the latest commit**.
- No failed project/file mappings attributable to the optimization; verify repeated runs do not duplicate metadata or MinIO objects.
- Stable peak memory and acceptable garbage collection pauses on representative staging fixtures, including large Base64 blobs.
- Compare median transfer rates across repeated equivalent runs (not a single short burst); no made-up speedup percentage.
- Reconcile DONE links with MinIO, investigate `UNKNOWN` checks, and manually review any orphan candidates. Neither inventory endpoint authorizes deletion.
- Deploy only after a backup, controlled migration window and documented rollback plan.

## Known unrelated CI issue

The real-Tomcat export streaming integration test has intermittently failed with `MimeHeaders` NPE / premature HTTP EOF in CI, even when migration E2E tests pass. Investigate the Spring Security + servlet ASYNC response-header lifecycle and fix it separately; do not disable the test to make the migration PR green.
