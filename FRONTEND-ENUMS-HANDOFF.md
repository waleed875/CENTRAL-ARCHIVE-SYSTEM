# Enum catalog — frontend handoff

Branch: `feat/legacy-migration-settings`

Everything here is additive except one field that got stricter. A client that keeps its current
hardcoded values keeps working; the point of the catalog is that it no longer has to.

## What changed in the backend

### New: `GET /api/v1/enums`

Returns every set of values the API restricts a field to, in one call. Readable by any
authenticated user — the same access as the reference-data endpoints it complements, since a user
who cannot open the organisations screen does not need the organisation types either. It returns
401 without a session.

```json
{
  "success": true,
  "data": {
    "projectStatus": ["DRAFT", "IMPORTED", "UNDER_REVIEW", "NEEDS_CORRECTION", "REJECTED", "APPROVED", "ARCHIVED"],
    "dataCompletionStatus": ["COMPLETE", "INCOMPLETE"],
    "projectSourceType": ["MANUAL", "BRANCH_PACKAGE", "MIGRATION"],
    "userStatus": ["ACTIVE", "DISABLED", "LOCKED"],
    "organizationType": ["COMPANY", "FACTORY", "LINE", "DEPARTMENT"],
    "migrationSourceType": ["POSTGRESQL", "LEGACY_DB"],
    "migrationBatchStatus": ["PLANNED", "RUNNING", "SUCCESS", "FAILED", "PARTIAL"],
    "migrationBatchCompletionStatus": ["SUCCESS", "PARTIAL", "FAILED"],
    "packageStatus": ["RECEIVED", "VERIFIED", "IMPORTED", "FAILED", "DUPLICATE"],
    "fileStatus": ["ACTIVE", "DELETED"],
    "auditAction": ["REJECT", "APPROVE", "ARCHIVE", "IMPORT", "CREATE", "UPDATE", "DELETE", "READ", "EXECUTE"],
    "auditEntityType": ["Auth", "User", "Role", "Organization", "ConfidentialityLevel", "Project", "ProjectMember", "ProjectType", "FileCategory", "File", "BranchPackage", "Export", "Migration", "MigrationSettings", "AuditLog", "Dashboard"],
    "projectSortFields": ["createdAt", "name", "projectCode", "projectNumber", "status", "updatedAt", "versionNo", "year"],
    "sortDirections": ["asc", "desc"]
  }
}
```

The keys are the contract. The values are read from the Java enums the server validates against, so
a value cannot go stale on the server side without appearing here on the next deploy.

**Fetch it once** and cache it for the session. It is static reference data, and it is behind the
BFF like every other call, so it needs no special handling beyond an allow-list entry in
`lib/api/catalog.ts`.

### New: unpaginated selection lists

`/api/v1/enums` covers fields whose value is a **name the server invents** (a status, a type, a
sort field). It does not cover the fields whose value is a **row in another table** — those need an
`id`, and an enum cannot carry one. Four of those had no endpoint a dropdown could use: the only
call available was the paginated list, and a page boundary inside a dropdown drops a row the user
was entitled to pick, with nothing on screen saying which one went missing.

So each of the four now has an unpaginated sibling. **Add these and ignore the paginated ones for
dropdowns** — the paginated ones stay for the admin tables that page through them.

| Route | Replaces (for dropdowns) | Filters | Order |
|---|---|---|---|
| `GET /api/v1/confidentiality-levels/all` | `/confidentiality-levels?page=&size=` | none — see below | `levelOrder` ascending, so severity reads low → high |
| `GET /api/v1/organizations/all` | `/organizations?page=&size=` | `parentId`, `type`, `isActive` | `name` ascending |
| `GET /api/v1/roles/all` | `/roles?page=&limit=` | `isActive` | `code` ascending |
| `GET /api/v1/file-types/all` | `/file-types?page=&size=` | `isActive` | `name` ascending |

**The response is a bare array, not the paged envelope.** This is the one thing to get right:

```json
{ "success": true, "data": [ { "id": "...", "code": "SECRET", "name": "سري", "levelOrder": 3, "...": "..." } ] }
```

A client already written against the paginated sibling reads `data.content[i]`. Point it at `data`
instead. Element shapes are **identical** to the paginated responses — same records, same
`ConfidentialityLevelResponse` / `OrganizationResponse` / `RoleResponse` / `FileTypeResponse` — so
one TypeScript type covers both.

**Readability matches the paginated siblings**: any authenticated user, no role check, 401 without
a session. Nothing here is restricted to `system_admin`.

#### Which route a given dropdown calls

| Dropdown | Route | What you submit back |
|---|---|---|
| Project → confidentiality level | `/confidentiality-levels/all` | `confidentialityLevelId` (`id`) |
| Project → project type | `/project-types/all` *(already existed)* | `projectTypeId` (`id`) |
| Project → organization | `/organizations/all` | `organizationId` (`id`) |
| File → category | `/file-categories/all` *(already existed)* | `categoryId` (`id`) |
| File → type, **before** a category is chosen | `/file-types/all` | `typeId` (`id`) |
| File → type, **after** a category is chosen | `/file-types/by-category/{categoryId}` *(already existed)* | `typeId` (`id`) |
| User → organization | `/organizations/all` | `organizationId` (`id`) |
| User → max confidentiality level | `/confidentiality-levels/all` | `maxConfidentialityLevelId` (`id`) |
| User → roles | `/roles/all` | **`roleIds` — the `id`s** |

Everything submits an `id` **except roles**, where `roleIds` takes the `id` field of each chosen
row — not the code, even though the code is what `roles.create` validates the person-level code
against. Both `id` and `code` are in the response, so label a row however you like.

#### Three things that will bite otherwise

1. **Confidentiality levels have no `isActive`.** The entity has no such column, so the route takes
   no filter. Do not send one, and do not render a disabled state from a field that is never there.
2. **`organizations/all` without `parentId` returns every level at once**, parents included — a
   flat list with no hierarchy. For a cascading picker, pass the `parentId` of the level you just
   selected; for a searchable single select, take the flat list and indent by walking `parentId`.
   `/organizations/tree` remains the right call when you want the nesting delivered for you.
3. **`type` on `organizations/all` takes the enum names**, the same ones `/api/v1/enums` lists under
   `organizationType`, and matching is case-insensitive. Anything else throws and returns 500 — it
   is the one filter here that is not validated into a 400, the same as on the paginated endpoint it
   mirrors.

#### Catalog wiring

`lib/api/catalog.ts` gets four entries, and the existing `lookup` field kind in
`operation-definitions.ts` can point at them the way it already points at `projectTypes.all`:

```
{ key: "confidentialityLevels.all", method: "GET", path: "/api/v1/confidentiality-levels/all" }
{ key: "organizations.all",          method: "GET", path: "/api/v1/organizations/all" }
{ key: "roles.all",                  method: "GET", path: "/api/v1/roles/all" }
{ key: "fileTypes.all",              method: "GET", path: "/api/v1/file-types/all" }
```

The eleven existing lookups that still point at a paginated sibling. They work today, but each
fetches page one of twenty and would silently truncate a system with more rows than that:

| Lookup key | `operation-definitions.ts` lines | Repoint to |
|---|---|---|
| `confidentialityLevels.list` | `:31`, `:47`, `:48`, `:65`, `:67` | `confidentialityLevels.all` |
| `organizations.list` | `:29`, `:43`, `:47`, `:48` | `organizations.all` |
| `fileTypes.list` | `:65` | `fileTypes.all` — or `fileTypes.byCategory` once `:65`'s sibling `categoryId` on the same operation is filled in |
| `roles.list` | `:95` | `roles.all` |

`:43` (`organizations.create`'s `parentId`) and `:47`/`:48` (`users.create`/`users.update`'s
`organizationId`) are single-selects of the whole organisation list; `:29` is the same list on the
project form. None of them pass a filter today, so they will keep returning every level.

**`users.create`'s `roleIds` (`:47`) is not a lookup at all** — it is declared with the `ids` kind,
a free-text box for comma-separated identifiers. `LookupInput` renders a `<select>`, so the one
place that needs the new `roles.all` route is the one place that will not show it. Repointing `:95`
alone leaves the role picker on the user form still asking for a typed list of UUIDs.

Nothing in `catalog.ts` needs its existing keys changed — add the four, repoint the four lookup
keys, and both shapes keep working: `listFrom` in `components/operation-form.tsx:24` accepts a bare
array *and* an envelope, which is why the new routes need no change to `LookupInput` itself.

`lib/api/query-definitions.ts` needs no change — these are lookups for form fields, not filters.
Add them to `queryParams` only if you intend to filter a table by `parentId` or `isActive`.

## Field-by-field: what becomes a dropdown

Every row is a field that is a free-text input today. The "Key" column is the catalog key to read.

| Field | Where it is now | Key | Change |
|---|---|---|---|
| `dataCompletionStatus` | `operation-definitions.ts:37` | `dataCompletionStatus` | Text input → select. **Was a silent-failure risk**: a typo reached a Java enum and failed at request time after the form was filled. |
| `status` (user) | `operation-definitions.ts:48` | `userStatus` | Text input → select. Same risk. |
| `type` (organization) | `operation-definitions.ts:43` | `organizationType` | Text input → select. Same risk. |
| `sourceType` (migration source) | `operation-definitions.ts:68`, `:69` | `migrationSourceType` | Text input → select. **Also a backend behaviour change**, see below. |
| `status` (batch completion) | `operation-definitions.ts:73` | `migrationBatchCompletionStatus` | Text input → select. Offer **only** the three terminal states. Offering `PLANNED` or `RUNNING` returns 400. |
| `status` (project filter) | `query-definitions.ts:22`, `:23` | `projectStatus` | Filter input → select |
| `sourceType` (project filter) | `query-definitions.ts:23` | `projectSourceType` | Filter input → select |
| `status` (package filter) | `query-definitions.ts:26` | `packageStatus` | Filter input → select |
| `status` (file filter) | `query-definitions.ts:25` | `fileStatus` | Filter input → select |
| `status` (user filter) | `query-definitions.ts:15` | `userStatus` | Filter input → select |
| `type` (organization filter) | `query-definitions.ts:13` | `organizationType` | Filter input → select |
| `action` (audit filter) | `query-definitions.ts:28` | `auditAction` | Filter input → select |
| `entityType` (audit filter) | `query-definitions.ts:28` | `auditEntityType` | Filter input → select |
| `sortBy` | `query-definitions.ts:15`, `:23` | `projectSortFields` | Text input → select. Values outside the list are **not** rejected; the server silently sorts by `createdAt`, so the form is the only place this is visible. |
| `sortDirection`, `order` | `query-definitions.ts:15`, `:23` | `sortDirections` | Text input → select. `asc` / `desc` — anything else is read as `desc`. |
| `isActive`, `isLatestVersion` | `query-definitions.ts:13-20`, `:23` | — | Text input → `true`/`false` select, or a checkbox. |
| `page`, `size`, `limit`, `year` | every query | — | At minimum `type="number"`. |

## One field that gets stricter

### `migrationSources.sourceType`

The backend DTO took any string. The check ran when a batch was started, not when the source was
written, so a mistyped type was stored and only surfaced after the migration window had been opened
on a source nobody could run.

Now it is a Java enum (`POSTGRESQL`, `LEGACY_DB`) and **anything else is rejected with 400 at the
request**. `MigrationRunner` stamps `LEGACY_DB` on the source it auto-creates, so the
environment-default path is unaffected.

If your form can still submit an empty `sourceType`, expect a 400 rather than a stored blank. The
select makes that unreachable, which is the point.

## What did not change

- **Every existing endpoint is untouched.** Responses, status codes, and pagination are the same as
  before. The four `/all` routes are additions; nothing that answered before answers differently. A
  client that keeps its current hardcoded values and its current paginated lookups keeps working.
- **`ProjectMember.roleName` stays free text.** It is the person's role *inside the project*, not a
  system role — it has no entry in `core.roles` and the design describes it as open
  (`Central_Platform_ERD_MVP_AR.md:365`: "مشرف، منفذ، مدقق، مشارك **وغيرها**"). A restricted list
  would contradict the design and constrain stored data. If you want to help typing without
  constraining it, a `<datalist>` suggesting مشرف / منفذ / مدقق / مشارك is the shape that matches;
  the backend does not validate this field and needs no change for it.
- **`migrationSettings.defaultConfidentialityCode` is gone.** It was a run-wide code stamped onto
  every migrated file while its project kept no level, so the file listing dropped the files for any
  viewer whose clearance did not reach it — silently, leaving the project showing no files at all.
  Migrated files now inherit their project's level. The field is removed from the request, the
  response, the settings screen and `LEGACY_MIGRATION_DEFAULT_CONFIDENTIALITY_CODE`; the
  `import.migration_settings.default_confidentiality_code_encrypted` column is left in place,
  unmapped, so nothing has to be dropped. Classify a project from the project page instead.
- **No other existing endpoint changed shape.** Responses, status codes, and pagination are untouched.

## Suggested implementation

1. `lib/api/catalog.ts` — add `enums.list` → `GET /api/v1/enums`.
2. `lib/api/contracts.ts` — a Zod schema for the response, with the keys as a literal union so a
   removed key fails the build rather than rendering an empty dropdown.
3. `lib/api/operation-definitions.ts` — add `choices?: string[]` to `OperationField` and a
   `FieldKind` of `"select"`; fill in `choices` from the catalog per the table above. A test
   asserting every field with `choices` matches a catalog key keeps this from drifting.
4. `components/operation-form.tsx` — render `"select"` as a `<select>` with an empty first option
   for optional fields. The existing `lookup` path (lines 36-47) is the same shape and can share
   the styling.
5. `lib/api/query-definitions.ts` — `queryParams` is currently `string[]`, so filters have no place
   to carry options. Either widen it to `QueryParam[]` with an optional `key`, or keep a parallel
   map in the component. Widen the type; the `queryParams.includes(name)` check in
   `components/query-form.tsx:54` needs the name property instead.
6. `components/query-form.tsx:54` — render the select for params that have one, and keep `<input>`
   for the rest.
7. `settings/migration/page.tsx` — nothing to do here any more. The `confidentialityLevels.list`
   lookup it used to carry was the input for `defaultConfidentialityCode`, and that field is gone
   with the setting behind it.

## Verification

`postman/06_Enums.postman_collection.json` is new and asserts every key is present, non-empty, and
duplicate-free, and that the completion set excludes the non-terminal states.

`postman/04_Import_and_Migration.postman_collection.json` gained a negative case:
`Create Migration Source with an unsupported type` expects 400 on `"sourceType": "ORACLE"` — the
exact value the old `MigrationSourceRequest` documented as its example.

The schema-level guarantee lives in `V20__constrain_migration_source_type.sql`:
`PostgresSchemaIntegrationTest.migrationSourceTypeIsConstrainedToTheEnumValues` writes an
`ORACLE` row directly through JDBC and asserts PostgreSQL refuses it.

For the selection lists, `ReferenceLookupHttpIntegrationTest` is the check that matters, and it runs
over real HTTP rather than against the services:

- `theAllSegmentIsNotSwallowedByTheIdRoute` — each resource also has a `/{id}` route taking a UUID
  above `/all`. Had that path variable claimed the segment, the route would answer **400**, and a
  client would read it as a broken endpoint rather than as a routing mistake. This is exactly what a
  service-level test cannot catch.
- `everySelectionListAnswersWithABareArrayAndNotThePagedEnvelope` — asserts `data` is an array with
  no `content` key, so a client written against the paginated sibling fails here rather than
  rendering a silently empty dropdown.
- `narrowingAnOrganizationListReturnsOnlyThatType` — builds a four-level hierarchy
  (`COMPANY → FACTORY → LINE → DEPARTMENT`, each level the service requires of the one above), then
  asserts `?type=DEPARTMENT` keeps the department, returns nothing of another type, and is strictly
  smaller than the unfiltered list. It creates and deletes its own rows rather than counting seeded
  ones, so it does not depend on what the initializer happened to write.
- `aSelectionListIsNotServedToAnUnauthenticatedCaller` — 401 on all four.

Full suite: **251 tests, 0 failures, 0 errors** against real PostgreSQL and MinIO.

