# Backend functional audit and repair contract

Scope: `new-function-need-to-test` at `1ff7163de12b02f74ed6f2c667a05d890443b2bd`.
Repair branch: `fix/backend-functional-audit`; draft PR #25. No merge or deployment is part of this change.

## Verification procedure and evidence

Backend CI builds a pinned MinIO source release rather than downloading from the retired binary endpoint. PostgreSQL and MinIO are disposable test services. It runs `bash mvnw -B -ntp -DfailIfNoTests=true verify`, automatically discovering all Surefire test classes, and uploads XML reports and infrastructure logs even on failure. No existing test is disabled or excluded to make the build pass.

The baseline with CI repaired passed **293 tests in 48 suites**, with zero failures/errors/skips (run `37657952789`, artifact `11498629834`). These counts were recalculated directly from the archived Surefire XML; an earlier progress estimate of 318 was incorrect. A baseline pass is not evidence that later repairs pass. Use the latest Backend CI run for this PR and its archived XML for the final repaired result. Final verification evidence is recorded in the PR discussion without changing the tested code.

Existing suites cover authentication/revocation, role and object-level authorization, organizations/users/roles, project lifecycle/versioning, file upload/download and content inspection, MinIO integration/readiness, package verification and import/export, legacy migration, audit, dictionaries, database invariants and Flyway startup. New regression tests cover the repaired contracts below. They do not establish an exhaustive coverage percentage for every backend method or every possible input.

Companion: `postman/07_Backend_Functional_Regression.postman_collection.json`. This adds non-destructive login/read/error-contract checks for a configured test account. CI evidence comes from the Java tests; this report does not claim that the Postman collection was executed against a live server.

## Corrected contracts

**The following definitions supersede conflicting examples in earlier handoff/API-design documents for these endpoints.**

- Failed login for a wrong password, unknown username, or unavailable account: HTTP 401; `data.code=INVALID_CREDENTIALS`. Responses do not distinguish account existence. Invalid/missing request fields remain HTTP 400; unexpected server failures are not reclassified as bad credentials.
- `GET /api/v1/projects/review-queue`: system_admin or auditor, with normal confidentiality restrictions, including administrators. Parameters: optional organizationId and submittedBy; page defaults to 1, size to 20, capped at 100. Only latest UNDER_REVIEW projects are returned. Database ordering: submittedAt ascending, unknown timestamps last, id tie-breaker. No submission timestamp is fabricated for undated historical review rows.
- General project search also advertises/accepts `sortBy=submittedAt`.
- REJECTED remains terminal, as implemented before this repair. It cannot be edited, resubmitted, archived as approved, or used as a new-version source. The contradictory instruction to create a version from a rejected project has been removed. Request correction before final rejection when another editing round is intended. This does not introduce an undocumented reopening/appeal workflow.
- `PATCH /projects/{id}/reject` request remains `{ "rejection_reason": "..." }`. Response uses `data.rejectionReason`; `data.reviewNotes` remains the correction instruction. They are not aliases. Migration V24 copies historical rejection reasons into the new field without erasing old notes, overwriting existing explicit reasons, or changing V23's checksum. A regression test executes the shipped SQL twice against historical-shaped rows and checks preservation/idempotence.
- New versions may be created only from the latest approved/archived version. A draft successor cannot be deleted leaving the earlier chain without a latest row. This guards sequential misuse; it is not a claim of comprehensive concurrency verification.
- PATCH cannot erase required project name/number using empty or whitespace strings. Omitted fields remain unchanged. New-version overrides are validated too.
- Declared page/size/limit inputs must be positive integers; zero, negatives, nonnumeric input and integer overflow return 400. Existing maximum-size caps remain compatible. Pagination envelopes report executed size/page rather than an uncapped request value.
- `/enums` gains additive `dashboardAction` and `dashboardEntityType` lists. Old audit lists and existing dashboard event names remain unchanged. Consumers must use the vocabulary for their own screen rather than treating audit operations and dashboard activity as the same contract.
- Migration V25 adds AUDIO / MEDIA-AUDIO reference data without renaming/moving existing categories. This is taxonomy support, not speech transcription or codec validation.

## Scope limits and earlier review qualifications

The legacy adapter reads the repository's tbl_project schema. The available schema/test fixture does not establish a legacy project-type column or how its values map to the central dictionary. Do not invent a mapping from numeric IDs to seeded UUIDs. The earlier review's claim that legacy project types are definitely lost was stronger than the evidence. An actual source schema and approved mapping are required before changing that import contract. Existing legacy migration suites are included in the complete run; live legacy installations were not accessed.

The repository test suite and new regressions are not exhaustive input-space coverage, load testing, a large-production-database migration rehearsal, a disaster-recovery exercise, or proof that deployed dependencies have no vulnerabilities. No production database, live legacy source or user archive is used by CI.

Review Security Scan separately. Run `37660247845` reported zero findings for backend/pom.xml within its configured HIGH/CRITICAL, fixed-vulnerability filters, but three findings in frontend/package-lock.json (Next.js, sharp, source-map-js); its later secret/misconfiguration step did not run after the dependency gate failed. Therefore the whole repository must not be described as security-green. Frontend dependency changes are outside this backend-only patch.
