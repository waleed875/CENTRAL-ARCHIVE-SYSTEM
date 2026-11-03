# Backend hardening review

Base: `main` at `cd8a8c947ae4fdf2572bc1818769c7d028c6af7d`.
Branch: `fix/backend-security-schema-arabic`.
Review: https://github.com/abdullahhabbar-hub/CENTRAL-ARCHIVE-SYSTEM/pull/28

## Scope and implemented changes

Only `backend/Archive/Archive/**` is modified. Frontend, deployment and the existing V1-V27 migrations remain unchanged.

- Capture the submitting user's Spring Security context for `@Async` import execution and clear/restore it on worker completion. The executor retains an unbounded task queue; this change does not claim distributed-job durability or overload protection.
- Exclude inactive roles from authentication authorities and the current-user response.
- Apply one password policy to account creation and both password-change DTOs: at least eight Unicode code points, nonblank, at most 72 UTF-8 bytes for BCrypt. Arabic passwords are measured in bytes as well as characters.
- Return Arabic JSON error envelopes for filter-level 401/403 responses instead of container errors.
- Restrict migration connection metadata and batch endpoints to `system_admin`.
- Filter incomplete-project report rows in PostgreSQL before resolving names or computing aggregate counts. An administrator's role no longer bypasses confidentiality clearance in this report.
- Require `system_admin` plus the highest configured confidentiality clearance to read historical audit snapshots. This deliberately conservative policy avoids exposing previously classified fields through historical JSON. Per-event historical classification and selective audit views remain future work.
- Translate common success messages in response metadata without changing payloads or machine-readable enum codes.
- Add `GET /api/v1/enums/labels` with Arabic labels keyed by the existing enum identifiers. Existing `GET /api/v1/enums` remains unchanged. The frontend must explicitly consume the new label endpoint; no UI changes are included here.
- Reconcile encrypted packages using `decrypted-payload.zip` and `import-ready.pkg`; do not expect the intentionally unretained `original.pkg`. Detection remains read-only and never deletes objects.
- Allow administrators to list soft-deleted files explicitly with `status=DELETED`, while preserving active-only defaults and confidentiality filters. The file listing and audit routes cap page size at 100.

## V28 database migration

`V28__harden_reference_integrity.sql` adds forward-only constraints:

1. Restore the project completion-status check removed by V2's broad status-constraint replacement.
2. Enforce file type/category consistency with a composite foreign key and reject a type without a category.
3. Make `(package_id, local_project_uuid)` unique to protect source-project identity inside a package.
4. Enforce `singleton=true` in migration settings.
5. Restore missing actor foreign keys on pre-Flyway upgrades, without adding duplicates to fresh installations.

The migration fails closed when incompatible records exist. It does not merge identities, delete archive records, or guess their provenance. Take compatible PostgreSQL and MinIO backups and run the migration on a restored staging database before production. See `review-preflight.sql` for read-only checks.

## Regression coverage

The patch adds tests for Arabic UTF-8 password limits, the live change-password contract, inactive-role authorities, async context propagation/cleanup, Arabic security responses, audit clearance, report confidentiality, migration metadata authorization, Arabic enum coverage, V28 integrity constraints, encrypted storage reconciliation, classified asynchronous HTTP import, and deleted-file visibility.

The existing Arabic HTTP test that asserted the broken empty 401 envelope now asserts the corrected Arabic JSON contract. The seven report-service scenarios are retained with a clearance-aware repository fixture. No tests are disabled to obtain a green result.

Run with Java 21 and isolated PostgreSQL/MinIO using the existing Backend CI workflow or `bash mvnw -B -ntp verify` from this module. A successful historical main-branch run is not evidence for this patch. Inspect the Backend CI run associated with the final PR head before merging. Full tests were not executed in the editing container, where Maven/Docker and dependency-network access were unavailable.

## Remaining audit findings — not claimed fixed by this patch

- Project latest-version uniqueness and concurrent workflow updates need a separate transaction-aware migration/service change.
- Partial package import/file retries and legacy migration retry/restart recovery require end-to-end durability changes; these are not silently marked resolved.
- Trusted-terminal key enrollment/revocation and complete archive/KDF/frame resource budgets remain necessary security work.
- Bootstrap account reactivation behaviour, last-administrator protection, and login throttling require further changes.
- Dashboard event timestamps, persistent export failure handling, nullable metadata response fields, and server-side project completeness rules remain open.
- Historical V2/V19 provenance loss or incorrect attribution cannot be repaired safely by guessing; review actual historical data before attempting a corrective migration.
- Frontend dependency advisories, UI enum translation, and root CI workflow improvements are outside this backend-only patch.

Keep this PR as a draft until checks have been reviewed and the remaining production risks have been accepted or resolved. Do not treat this patch as a complete security certification.
