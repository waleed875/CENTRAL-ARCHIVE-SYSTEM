# CENTRAL-ARCHIVE-SYSTEM Repair Plan

Branch: `fix/foundation-security-phase1`

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

- [ ] Add a centralized authorization service.
- [ ] Enforce organization scope for project/file/package reads.
- [ ] Enforce maximum confidentiality level for projects and files.
- [ ] Enforce scope in list/search/stats endpoints, not only get-by-id.
- [ ] Prevent direct-object-reference access to cross-organization data.
- [ ] Review read-only role visibility.

## Phase 4 - Package Integrity and Import/Export

- [ ] Verify SHA-256 from decoded file content instead of trusting the manifest checksum.
- [ ] Fail package verification when any file integrity check fails.
- [ ] Validate package schema version against supported versions.
- [ ] Add package authenticity/signature support.
- [ ] Replace Base64-in-JSON packages with streamed archive packages (manifest + files).
- [ ] Remove full-file memory buffering during export/import.
- [ ] Make import state transitions transactional and recoverable.
- [ ] Record partial failures explicitly.

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
