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
- [ ] Add broader role and object-level integration tests (confidentiality unit coverage is in place).
- [x] Add confidentiality enforcement for project/file read paths; organization visibility remains intentionally global for central users per the documented MVP workflow.

## Phase 2 - Database and Entity Alignment

- [x] Align `project_uid` type and constraints between PostgreSQL and JPA.
- [x] Remove the incorrect single-column unique constraint on `project_uid`.
- [x] Enforce `UNIQUE(project_uid, version_no)`.
- [x] Align project/package/export actor references with authenticated user UUIDs.
- [x] Align branch package UUID, terminal UUID, receiver UUID, and related package fields with PostgreSQL.
- [x] Align `PackageProject` with central project IDs and source/local project UUID provenance.
- [~] Replace schema drift via `ddl-auto=update` with Flyway versioned migrations; V1/V2 are committed and PostgreSQL CI validation is running.
- [x] Set Hibernate schema mode to `validate` (including the default development path).

## Phase 3 - Authorization and Confidentiality

- [x] Keep the documented seven-role MVP model; no undocumented permissions subsystem added.
- [x] Add centralized confidentiality authorization while keeping role checks in documented method security.
- [x] Enforce `max_confidentiality_level_id` on project/file list, search, view, and download.
- [x] Apply confidentiality filtering in PostgreSQL list/search queries and direct get/download checks.
- [x] Review organization visibility rules; do not enforce source-organization isolation for central users in the current MVP.
- [x] Allow `read_only` users to view/download permitted data while retaining no write permissions.
- [x] Add direct-object-reference tests for project and file access.

## Phase 4 - Documented Offline Package Workflow

The implementation must match `API_ENDPOINTS_DESIGN.md`: the central system receives a real package file from an offline terminal, not only a JSON manifest.

- [x] Change `POST /api/v1/packages/receive` to accept a real `.pkg` package file instead of manifest-only JSON.
- [x] Store the original received package in MinIO before verification/import.
- [x] Extract `manifest.json` from the package and validate supported schema versions.
- [x] Calculate full-package SHA-256 separately from `manifest_hash`.
- [x] Reject duplicate receives using both `package_uuid` and `package_hash`.
- [x] Re-read the stored package during verify, re-extract the manifest, and verify each file checksum and size.
- [x] Fail verification on package hash, manifest hash, schema compatibility, missing files, checksum mismatch, or size mismatch.
- [x] Keep the documented package states in the entity/schema.
- [x] Reject import requests unless the package is `VERIFIED`.
- [x] Create and complete `import_operations` with `STARTED`, `SUCCESS`, `PARTIAL`, or `FAILED`.
- [x] Import projects with status `IMPORTED` only.
- [x] Stream imported files into MinIO and create package/project provenance links after central project creation.
- [x] Record authenticated central user UUIDs for receive/import operations.
- [x] Remove Base64 file bodies from the package contract and stream archive entries to/from MinIO.
- [x] Preserve the original imported package object in MinIO; formal retention policy remains pending.

## Phase 5 - File Handling

- [x] Stream SHA-256 calculation for uploads.
- [x] Configure environment-driven maximum package upload/request sizes.
- [ ] Validate allowed file types using content inspection, not filename only.
- [x] Use UTF-8-safe `Content-Disposition` generation for downloads.
- [x] Ensure soft-deleted files cannot be downloaded through direct IDs.
- [x] Remove newly uploaded MinIO objects when subsequent database persistence fails; broader reconciliation job remains future work.

## Phase 6 - Audit Reliability

- [x] Redact common sensitive fields from serialized audit results.
- [x] Store the real authenticated user UUID in AOP-generated audit records.
- [x] Capture request IP from the servlet request and make forwarded-header trust an explicit production setting.
- [x] Make audit serialization bounded to avoid huge payloads.
- [x] Ensure audit logging redacts credentials, token/secret fields, embedded file/package byte fields, and manifest payload fields.
- [ ] Define retention and immutability strategy.

## Phase 7 - Tests and CI

- [x] Add unit tests for JWT access/refresh token separation and tamper rejection.
- [ ] Add authentication/authorization integration tests.
- [x] Add project versioning, PATCH-state, and confidentiality-aware statistics tests.
- [ ] Add PostgreSQL integration tests with Testcontainers.
- [ ] Add MinIO integration tests.
- [x] Add package verification and export/import round-trip integrity tests.
- [x] Add GitHub Actions for backend compile and focused unit tests.
- [ ] Add dependency/security scanning.

### Additional completed API alignment

- [x] Make file metadata PATCH partial with a dedicated update DTO.
- [x] Make project PATCH partial as documented.
- [x] Restrict UNDER_REVIEW project edits to description/notes.
- [x] Require new versions instead of editing APPROVED/ARCHIVED projects.
- [x] Create real streamed export .pkg archives and make them downloadable.
- [x] Validate project/file confidentiality before package export.
- [x] Add stable archive instance UUID configuration for package manifests.

## Phase 8 - Production Configuration

- [x] Add a production profile that requires DB/JWT/MinIO/CORS/archive-instance configuration without development fallbacks; bootstrap admin is disabled by default.
- [x] Make CORS origins environment-configurable.
- [x] Disable Swagger/API docs by default in the production profile.
- [x] Disable bootstrap admin by default in production.
- [ ] Add structured logging; health/readiness endpoints are now exposed through Actuator with details hidden.
- [x] Add deployment documentation and PostgreSQL/MinIO backup and restore procedures in `DEPLOYMENT.md`.

## Delivery rule

Each phase should remain independently reviewable. Security fixes, database migrations, and large package-format changes should be committed separately so failures can be isolated and reverted safely.
