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

Status: COMPLETE

- [x] Enable Spring asynchronous execution.
- [x] Return refresh tokens to clients.
- [x] Distinguish access tokens from refresh tokens using a token-type claim.
- [x] Prevent refresh tokens from authenticating normal API requests.
- [x] Allow `/api/v1/auth/refresh` without a valid access token.
- [x] Rotate access/refresh tokens when refresh succeeds.
- [x] Persist JWT revocation cutoff per user so logout, password changes, and refresh rotation invalidate previously issued tokens.
- [x] Redact password/token/authorization/secret fields from audit payloads.
- [x] Resolve the authenticated user centrally instead of trusting client-supplied usernames.
- [x] Populate uploaded_by/deleted_by and operation actor fields from authenticated identity; database UUID alignment for remaining actor columns continues in Phase 2.
- [x] Add broader role and object-level integration tests, including real HTTP confidentiality/IDOR coverage for direct project/file access and filtered project lists.
- [x] Add confidentiality enforcement for project/file read paths; organization visibility remains intentionally global for central users per the documented MVP workflow.

## Phase 2 - Database and Entity Alignment

- [x] Align `project_uid` type and constraints between PostgreSQL and JPA.
- [x] Remove the incorrect single-column unique constraint on `project_uid`.
- [x] Enforce `UNIQUE(project_uid, version_no)`.
- [x] Align project/package/export actor references with authenticated user UUIDs.
- [x] Align branch package UUID, terminal UUID, receiver UUID, and related package fields with PostgreSQL.
- [x] Align `PackageProject` with central project IDs and source/local project UUID provenance.
- [~] Replace schema drift via `ddl-auto=update` with Flyway versioned migrations; V1-V10 are committed. V9 normalizes migration batch identifiers and V10 prevents parallel package imports; final CI validation for the latest migrations is in progress.
- [x] Set Hibernate schema mode to `validate` (including the default development path).

## Phase 3 - Authorization and Confidentiality

- [x] Keep the documented seven-role MVP model; no undocumented permissions subsystem added.
- [x] Add centralized confidentiality authorization while keeping role checks in documented method security.
- [x] Enforce `max_confidentiality_level_id` on project/file list, search, view, and download.
- [x] Apply confidentiality filtering in PostgreSQL list/search queries and direct get/download checks.
- [x] Review organization visibility rules; do not enforce source-organization isolation for central users in the current MVP.
- [x] Allow `read_only` users to view/download permitted data while retaining no write permissions.
- [x] Add direct-object-reference tests for project and file access.
- [x] Enforce project-member confidentiality checks and prevent member changes after APPROVED/ARCHIVED.
- [x] Enforce project file immutability after APPROVED/ARCHIVED.

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
- [x] Validate allowed file types using content inspection, not filename only; direct uploads and imported `.pkg` verification now enforce extension/content consistency, executable rejection, Office ZIP structure, and bounded archive expansion in a single streaming pass.
- [x] Use UTF-8-safe `Content-Disposition` generation for downloads.
- [x] Ensure soft-deleted files cannot be downloaded through direct IDs.
- [x] Remove newly uploaded MinIO objects when subsequent database persistence fails for both manual uploads and package imports; broader reconciliation job remains future work.

## Phase 6 - Audit Reliability

- [x] Redact common sensitive fields from serialized audit results.
- [x] Store the real authenticated user UUID in AOP-generated audit records.
- [x] Capture request IP from the servlet request and make forwarded-header trust an explicit production setting.
- [x] Make audit serialization bounded to avoid huge payloads.
- [x] Ensure audit logging redacts credentials, token/secret fields, embedded file/package byte fields, and manifest payload fields.
- [x] Define retention and immutability strategy; V8 installs an append-only PostgreSQL trigger with explicit controlled retention-purge mode, `DEPLOYMENT.md` documents the purge procedure, and PostgreSQL CI validation is green.

## Phase 7 - Tests and CI

- [x] Add unit tests for JWT access/refresh token separation and tamper rejection.
- [x] Add authentication/authorization integration tests covering real HTTP login/logout/revocation and method-role enforcement (`read_only` forbidden vs `system_admin` allowed).
- [x] Add project versioning, PATCH-state, and confidentiality-aware statistics tests.
- [x] Add PostgreSQL integration tests against the real PostgreSQL 17 CI service, including Flyway version, critical column types, project-version uniqueness, and audit-trigger validation.
- [x] Add MinIO integration tests with a real MinIO service covering upload, download, presigned URL generation, and delete.
- [x] Add package verification and export/import round-trip integrity tests.
- [x] Add GitHub Actions for backend compile and focused unit tests.
- [x] Add Trivy dependency, secret, and misconfiguration scanning; it identified CVE-2025-59952 in MinIO 8.5.12, which was upgraded to MinIO SDK 8.6.0. Current security scan is green.

### Additional completed API alignment

- [x] Allow imported projects to enter the documented central review workflow.
- [x] Restrict new project versions to APPROVED/ARCHIVED projects, use max existing version + 1, attribute the current creator, and copy members without copying files.
- [x] Correct audit entity attribution/unwrapping for controller responses.
- [x] Align project search with documented filters for type, confidentiality, source, project UID, creator, approver, and latest-version selection.
- [x] Enforce organization hierarchy rules, safe deactivation, correct project counts, partial PATCH semantics, and database-level pagination filtering.
- [x] Align file category/type PATCH, filtering, and DELETE semantics with the API contract: immutable codes/category links, database-side filters, and soft deactivation guarded by active-file usage.

- [x] Make file metadata PATCH partial with a dedicated update DTO.
- [x] Make project PATCH partial as documented.
- [x] Restrict UNDER_REVIEW project edits to description/notes.
- [x] Require new versions instead of editing APPROVED/ARCHIVED projects.
- [x] Create real streamed export .pkg archives and make them downloadable.
- [x] Validate project/file confidentiality before package export.
- [x] Add stable archive instance UUID configuration for package manifests.

## Pre-merge blocker discovered during final API audit

- [ ] Align `POST /migration-batches/:id/run` with the documented migration contract. The current legacy runner uses the configured staging datasource (`LEGACY_DB_*`) and does not consume the dynamic `connection_string`, `file_path`, or mapping values accepted by `MigrationRunRequest`. Do not represent dynamic PostgreSQL/File migration adapters as implemented until real adapters and validation exist.
- [x] Align migration batch lifecycle DTOs and controller/service signatures.
- [x] Normalize `migration_batches.batch_number` to `VARCHAR(80)` and enforce uniqueness per source.
- [x] Prevent deleting migration sources while running or historical batches exist.
- [x] Make migration source PATCH partial.

## Phase 8 - Production Configuration

- [x] Add a production profile that requires DB/JWT/MinIO/CORS/archive-instance configuration without development fallbacks; bootstrap admin is disabled by default.
- [x] Make CORS origins environment-configurable.
- [x] Disable Swagger/API docs by default in the production profile.
- [x] Disable bootstrap admin by default in production.
- [x] Add structured production JSON logging (Logstash format) with sanitized `X-Request-ID` correlation IDs; health/readiness endpoints remain exposed through Actuator with details hidden.
- [x] Add deployment documentation and PostgreSQL/MinIO backup and restore procedures in `DEPLOYMENT.md`.

## Delivery rule

Each phase should remain independently reviewable. Security fixes, database migrations, and large package-format changes should be committed separately so failures can be isolated and reverted safely.
