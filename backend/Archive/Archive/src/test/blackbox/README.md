# Live backend acceptance tests

`live_api_smoke.py` starts the packaged application JAR on loopback and exercises real HTTP requests. It does not use MockMvc, call application services, or write application rows through repositories. Independent SQL reads verify persistence.

## Isolation and legacy source

Run through Backend CI, never against a production server. The script refuses to run without the explicit disposable-GitHub-Actions guard. It creates three new databases without dropping existing databases: a synthetic old source, a restored old source and a fresh destination. The old source uses the repository's `tbl_side`, `tbl_project` and `tbl_files` schema. A custom-format `pg_dump` is restored with strict `pg_restore --exit-on-error` before migration begins.

The PostgreSQL command adapter uses client tools inside the exact service container; the runner's multiple client versions/alternatives are not trusted. Backup bytes are streamed and remain in the temporary evidence directory.

This is a real migration of a synthetic restored database, NOT a test of the user's production backup, unknown legacy schema, or production data volume.

## Executed scenarios

- COMPANY -> FACTORY -> LINE -> DEPARTMENT creation, database parent/type verification, tree/path retrieval, and type selection.
- Both `parentId` and documented `parent_id` request fields. Non-company roots and wrong parents remain invalid.
- Create data-entry and read-only users over HTTP, then authenticate those new accounts and retrieve their profiles.
- Create/read/edit/delete standalone draft projects associated with each of the four organization types. Confirm GET 404, database removal and retained DELETE audit entries.
- Refuse read-only writes and deletion of under-review, approved, archived and imported projects.
- Add/remove a member and test project deletion guards; upload/download/soft-delete/restore real file bytes; preserve the historical-file project deletion guard.
- Create a stored encrypted legacy source, create/run/poll a migration batch, compare migrated files byte-for-byte, correct an incomplete migrated project and submit it through HTTP.
- Repeat migration to check project/file idempotence; compare source-table fingerprints before and after.

`OrganizationSocketHttpTest` adds real TCP regression tests for hierarchy aliases, selection filters, `limit`, `is_active` updates, inactive parents and conflicting query aliases.

## Evidence

The CI artifact contains `live-api-results.json` with expected/actual HTTP statuses, redacted request/response snapshots, persistence assertions and fixture identifiers. It also contains application logs, console output and the synthetic database dump. Passwords/tokens are redacted in the JSON. Check the latest commit/run: an old green run is not evidence for a new commit. The total check count includes both HTTP requests and independent data assertions; it is not a count of distinct API endpoints.

## Organization contract

A company is a root. A factory requires a company parent; a line requires a factory parent; a department requires a line parent. The parent must be active. Both parent field spellings are supported without changing these rules. Query alias pairs must agree when both are provided. `size` takes precedence over `limit`.
