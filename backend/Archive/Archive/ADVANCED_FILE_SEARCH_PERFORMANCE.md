# Global filename search: performance and rollout checklist

The feature is implemented by `GET /api/v1/files/search`, with authorization
and filtering inside the PostgreSQL query. This document is operational guidance,
not a request to run changes on a production database.

## First: measure real query plans

In staging, use representative data volume, file-name lengths and user roles.
Record `EXPLAIN (ANALYZE, BUFFERS)` for:
- Filename alone (Arabic, Latin, substring in the middle).
- Filename and extension.
- Filename and organization/year/category.
- A request for a later page, with and without previous versions.
- A low-clearance user with many inaccessible files.
- An empty filename search with only active/current filters.

Measure page and COUNT queries separately; page-size is capped by the server at
100. Check median and p95 response time, buffer hits and rows removed by filter.
Avoid running ANALYZE on very heavy production requests without a maintenance
window.

## Optional optimization (requires administrator approval)

The matching predicate is `LOWER(original_name) LIKE '%term%'`. A standard
B-tree index cannot generally accelerate arbitrary substring search.
If real query plans show this is the bottleneck, PostgreSQL `pg_trgm` could help:

```sql
-- Examples only. Do not run automatically as part of Flyway deployment.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- CONCURRENTLY must run outside a transaction. Confirm disk space and
-- concurrent-index permissions, and test the plan in staging first.
CREATE INDEX CONCURRENTLY IF NOT EXISTS
    ix_files_active_original_name_trgm
ON project.files USING gin (lower(original_name) gin_trgm_ops)
WHERE deleted_at IS NULL AND upper(status) = 'ACTIVE';
```

Indexes on `project_id` and project organization/year already exist. Validate
whether additional composite indexes are actually needed; every index adds
write cost and maintenance overhead. Confirm `EXPLAIN` shows the expected
partial predicate and index usage. Very short (<3-character) search terms and
wide-open queries may still require scans.

## Security and UX acceptance

- Results and counts must exclude files the user cannot access at both file
  and project confidentiality levels.
- No deleted files or discarded draft projects.
- Exact `%`, `_` and `!` in a user query must not behave as SQL wildcards.
- Opening preview must reuse server-side permission checks.
- Returning to file search from a project and browser back/forward must retain
  term, filters, sort and page.
- Header search on an already open `/files` screen must replace its results.
- Test with 10k, 100k and (if representative) 1M file rows in a controlled
  environment before applying a performance change to production.
