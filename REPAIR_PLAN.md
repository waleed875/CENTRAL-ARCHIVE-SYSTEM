# CENTRAL-ARCHIVE-SYSTEM Repair Plan

Branch: `fix/foundation-security-phase1`

## Source of truth

Repair work must follow the repository design documents before introducing new workflow concepts:

- `مخطط سير عمل إدارة المشاريع والملفات.md`
- `Central_Platform_ERD/API_ENDPOINTS_DESIGN.md`
- `Central_Platform_ERD_MVP_AR.md`
- `Central_Platform_ERD/central_platform_mvp_postgresql.sql`

For the MVP, do not introduce project-assignment tables, detailed permission tables, or undocumented package statuses unless the requirements are explicitly changed.

The documented package flow is:

`RECEIVED -> VERIFIED -> IMPORTED`

with `FAILED` and `DUPLICATE` as failure/duplicate states. The central import officer manually receives, verifies, and starts the import; branch terminals are not directly connected to the central application.

## Goal

Move the current backend from MVP-level implementation toward a safe, testable production baseline without mixing unrelated feature work.

## Phase 1 - Security and Runtime Foundation

Status: IN PROGRESS

- [x] Enable Spring asynchronous execution.
- [x] Return refresh tokens to clients.
- [x] Distinguish access tokens from refresh tokens using a token-type claim.
- [x] Prevent refresh tokens from authenticating normal API requests.
- [x] Allow `/api/v1/auth/refresh` without a valid access token.
- [x] Rotate access/refresh tokens when refresh succeeds.
- [x] Redact password/token/authorization/secret fields from audit payloads.
- [x] Resolve the authenticated user centrally instead of trusting client-supplied usernames.
- [x] Populate uploaded_by/deleted_by and operation actor fields from authenticated identity; database UUID alignment for remaining actor columns continues in Phase 2.
- [ ] Add role and object-level authorization tests.
- [ ] Add organization/confidentiality enforcement.

## Phase 2 - Database and Entity Alignment

- [x] Align `project_uid` type and constraints between PostgreSQL and JPA.
- [x] Remove the incorrect single-column unique constraint on `project_uid`.
- [x] Enforce `UNIQUE(project_uid, version_no)`.
- [ ] Align user-reference columns (`created_by`, `approved_by`, etc.) with Java types.
- [ ] Align branch package IDs and terminal IDs with the PostgreSQL schema.
- [ ] Align `PackageProject` fields with the real import lifecycle.
- [ ] Replace schema drift via `ddl-auto=update` with versioned migrations.
- [ ] Set production Hibernate mode to `validate`.

## Phase 3 - Authorization and Confidentiality

- [ ] Keep the documented seven-role MVP model; do not add a detailed permissions subsystem yet.
- [ ] Add a centralized authorization service for documented role checks and confidentiality checks.
- [ ] Enforce `max_confidentiality_level_id` on project/file search, view, and download.
- [ ] Apply confidentiality filtering in list/search results as well as direct get/download endpoints.
- [ ] Review organization visibility rules against the documented central-user workflow before enforcing organization isolation.
- [ ] Review `read_only` visibility against the documented allowed-data rule.
- [ ] Add direct-object-reference tests for project and file access.

## Phase 4 - Documented Offline Package Workflow

The implementation must match `API_ENDPOINTS_DESIGN.md`: the central system receives a real package file from an offline terminal, not only a JSON manifest.

- [ ] Change `POST /api/v1/packages/receive` to accept the documented package file (for example `.pkg`) instead of a manifest-only JSON upload.
- [ ] Store the received package temporarily or in MinIO before import.
- [ ] Extract the manifest from the received package and validate its schema version.
- [ ] Calculate `package_hash` from the complete received package; keep `manifest_hash` separate.
- [ ] Detect duplicates using both `package_uuid` and `package_hash`.
- [ ] Make `POST /packages/{id}/verify` re-read the stored package, re-extract the manifest, and verify every contained file checksum.
- [ ] Fail verification when package hash, manifest, schema compatibility, or any file checksum is invalid.
- [ ] Keep the documented states `RECEIVED`, `VERIFIED`, `IMPORTED`, `FAILED`, and `DUPLICATE`.
- [ ] Allow import only from `VERIFIED`.
- [ ] Create an `import_operations` row with `STARTED`, then complete it as `SUCCESS`, `PARTIAL`, or `FAILED`.
- [ ] Import projects with status `IMPORTED`; they must not become approved or archived automatically.
- [ ] Store imported files in MinIO and create `package_projects` provenance links.
- [ ] Record the authenticated central user as `received_by` / `imported_by`.
- [ ] Stream package/file content where practical instead of embedding full file bodies as Base64 inside manifest JSON.
- [ ] Preserve the original package or an immutable package object for traceability according to retention policy.

## Phase 5 - File Handling

- [ ] Stream SHA-256 calculation for uploads.
- [ ] Configure maximum upload/request sizes.
- [ ] Validate allowed file types using content inspection, not filename only.
- [ ] Sanitize download filenames and content disposition.
- [ ] Ensure soft-deleted files cannot be downloaded through direct IDs.
- [ ] Add object-storage reconciliation for database/storage failures.

## Phase 6 - Audit Reliability

- [x] Redact common sensitive fields from serialized audit results.
- [ ] Store the real user UUID in audit records.
- [ ] Capture request IP safely behind configured proxies.
- [ ] Make audit serialization bounded to avoid huge payloads.
- [ ] Ensure audit logging cannot recursively expose credentials or file contents.
- [ ] Define retention and immutability strategy.

## Phase 7 - Tests and CI

- [ ] Add unit tests for JWT token types and refresh behavior.
- [ ] Add authentication/authorization integration tests.
- [ ] Add project workflow/versioning tests.
- [ ] Add PostgreSQL integration tests with Testcontainers.
- [ ] Add MinIO integration tests.
- [ ] Add package import/export integrity tests.
- [ ] Add GitHub Actions for build + tests.
- [ ] Add dependency/security scanning.

## Phase 8 - Production Configuration

- [ ] Require JWT/DB/MinIO/bootstrap secrets outside local development.
- [ ] Make CORS origins environment-configurable.
- [ ] Disable or protect Swagger in production.
- [ ] Disable bootstrap admin after provisioning.
- [ ] Add structured logging and health/readiness endpoints.
- [ ] Add deployment documentation and backup/restore procedures.

## Delivery rule

Each phase should remain independently reviewable. Security fixes, database migrations, and large package-format changes should be committed separately so failures can be isolated and reverted safely.
