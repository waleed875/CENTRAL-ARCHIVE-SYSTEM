# Enum catalog — frontend handoff

Branch: `feat/legacy-migration-settings`

Everything here is additive except one field that got stricter. A client that keeps its current
hardcoded values keeps working; the point of the catalog is that it no longer has to.

## Error messages are now Arabic and name the field

This is a separate change from the enum catalog, but it changes the same response envelope, so a
client reading errors needs it.

**`message` is Arabic.** Every validation message, and every message the exception handlers produce,
is written in Arabic. There is no English variant and no locale parameter — the decision was to keep
the text in the backend so that any client (this one, Postman, a future mobile app) reads it the
same way without a translation table of its own. If you would rather have codes and translate on
your side, say so; it is a different design, not a tweak.

**`message` is no longer a fixed sentence when there is exactly one bad field.** With one field
failing, `message` *is* that field's sentence. With several, it is a count and `details` carries
them. So a form can show `message` as the headline and each `details` entry under its own input,
without checking how many there are.

**A duplicate or a bad reference now names the field.** Both were one fixed English sentence before,
which is the case this fixes. `409` on a duplicate organization code now reads:

```json
{
  "success": false,
  "message": "رمز المؤسسة «و-خ-001» مستخدم من قبل",
  "data": {
    "code": "DATA_INTEGRITY_CONFLICT",
    "message": "رمز المؤسسة «و-خ-001» مستخدم من قبل",
    "details": [{ "field": "code", "message": "رمز المؤسسة «و-خ-001» مستخدم من قبل" }]
  }
}
```

`details[].field` is the **request field name in camelCase** — the key you send — so it binds
directly to a form control. A nested one comes as the path Jackson reports, e.g.
`members[1].person_name` or `projectIds[0]`, with the index included, so a request of forty ids can
tell you which one was wrong. Split on the `.` and the `[n]` to index a list yourself before
attaching the message to a row.

Bind `details[].field` to your input name. `message` alone is a summary and will not tell a user
which box to fix.

**A detail with `field: ""` is possible** — the server enforces some rules itself and has no field
to name. Its `message` is still worth showing, so render it without attaching it to an input rather
than dropping it. `details` is `null`, not `[]`, when there is nothing to attach anything to.

**A duplicate organization code changed status: 400 → 409.** It used to be an
`IllegalArgumentException` (`400 INVALID_ARGUMENT`) while a taken *username* or *email* was already
`409 CONFLICT`, so the same mistake answered differently depending on the field. It is now
`ConflictException` like the others, which is both the correct status and the one that already has
a field to report. **If you branch on 400 for a duplicate code, that branch is now dead.**

**A duplicate can arrive under two `code` values, and your form must handle both.** The services
check for a taken value first and throw `ConflictException` (`409`, `code: "CONFLICT"`), which is
what nearly every duplicate you will see is. A check that loses a race is caught by the unique
index instead (`409`, `code: "DATA_INTEGRITY_CONFLICT"`). Both are 409 and both now carry
`details[].field`, so keying off the status and reading the field works for both — keying off
`data.code` and expecting one value does not.

```json
{
  "success": false,
  "message": "رمز المؤسسة «و-خ-001» مستخدم من قبل",
  "data": {
    "code": "CONFLICT",
    "message": "رمز المؤسسة «و-خ-001» مستخدم من قبل",
    "details": [{ "field": "code", "message": "رمز المؤسسة «و-خ-001» مستخدم من قبل" }]
  }
}
```

**`404` names the field too.** `ResourceNotFoundException` now reports which lookup missed, e.g.
`{"field": "id", "message": "المشروع غير موجود"}`. The id is not quoted back — you sent it, so
echoing it tells you nothing — but a named field such as an email is.

**Three project fields got stricter**, and this one *is* a behaviour change rather than additive.
`@Size` on `name` was 255 while the column is 250, and on `executingEntity` / `supervisorName` was 255
while the column is 200. A 251-character project name therefore passed validation and then hit the
database, coming back as a 409 `DATA_INTEGRITY_CONFLICT` — the same response as a duplicate project
number, with nothing to tell the two apart. The limits now match the columns: **250, 200, 200**. No
stored data is affected, since a longer value could never have been stored anyway; only new writes
are rejected earlier and more clearly. Worth checking any form that validates length client-side
against the old numbers.

**`422` messages on the user, role and password paths are Arabic now** (`BUSINESS_RULE_VIOLATION`),
e.g. `كلمة المرور الحالية غير صحيحة` and `لا يمكن إسناد دور مُعطّل`. The status did not change.

**Three behaviours worth not depending on:**

- The offending **value is quoted back** for a duplicate, but only for `code`, `username`, `email`
  and `project_number`. Nothing else is echoed — do not expect to see what collided on a field like
  `name`, and do not build a UI that assumes the value is there.
- A constraint the **server** sets on its own (`created_by`, `project_uid`) names no field at all.
  The response is a plain Arabic sentence with `details: null`. A form that assumes `details` is
  always present will break on it.
- A rejected **enum** value lists the allowed set, e.g. `قيمة نوع المؤسسة غير صالحة. القيم المقبولة:
  COMPANY أو FACTORY أو LINE أو DEPARTMENT`. This makes `/api/v1/enums` and the error message two
  sources for the same list; the message is a safety net, not the place to read the list from.
- **`401` and `403` do not carry the envelope at all.** They come from the security filter chain
  rather than from the exception handler, so there is no `code` and no `message` — the body is
  whatever the container's error page produced, usually empty. Your client already substitutes a
  fixed sentence for 403 for this reason; do the same for 401, which today shows "تعذر إكمال الطلب."
  for the one case where the reason is always the same.
- **`400` arrives with three different `code` values** and they are not interchangeable:
  `VALIDATION_FAILED` means the request was rejected before the service ran, `INVALID_ARGUMENT` means
  the service read it and rejected a value, and `INVALID_REQUEST_BODY` means a value could not be
  read at all. All three now name the field, so branch on `details[].field` and not on the code.

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

#### Frontend wiring — done, in `CENTRAL-ARCHIVE-FRONTEND-TEST`

Four entries added to `lib/api/catalog.ts`, adjacent to their `.list` siblings:

| Key | Path | Query page filters |
|---|---|---|
| `confidentialityLevels.all` | `/api/v1/confidentiality-levels/all` | none |
| `organizations.all` | `/api/v1/organizations/all` | `parentId`, `type`, `isActive` |
| `roles.all` | `/api/v1/roles/all` | `isActive` |
| `fileTypes.all` | `/api/v1/file-types/all` | `isActive` |

All eleven lookups that pointed at a paginated sibling now point at these. Each had been fetching
page one of twenty, so a system with more rows than that lost the rest without any visible sign:

| Repointed from | `operation-definitions.ts` lines |
|---|---|
| `confidentialityLevels.list` | `:31`, `:47`, `:48`, `:65`, `:67` |
| `organizations.list` | `:29`, `:43`, `:47`, `:48` |
| `fileTypes.list` | `:65` |
| `roles.list` | `:95` |

`:43` (`organizations.create`'s `parentId`) and `:47`/`:48` (`users.create`/`users.update`'s
`organizationId`) are single-selects of the whole organisation list; `:29` is the same list on the
project form. None of them pass a filter, so they keep returning every level.

**`users.create`'s `roleIds` (`:47`) needed a component change, not just a key swap.** It was the one
field that had to become a picker and was declared with the `ids` kind — a free-text box for
comma-separated identifiers. `LookupInput` renders a single-value `<select>`, so pointing it at
`roles.all` would have offered exactly one role for a field the API reads as a list. `LookupInput`
now branches on `field.kind === "ids"` and renders a `<select multiple>`, keeping the value a
comma-joined string — which is what the submit path at `operation-form.tsx:150` already splits, so
nothing downstream of the input changed. The label went from "معرّفات الأدوار، مفصولة بفاصلة"
(comma-separated role identifiers) to "الأدوار", since there are no longer identifiers to type.

`exportPackages.create`'s `projectIds` (`:74`) is the other `ids` field and deliberately keeps its
free-text box: projects have no unpaginated route, and a multi-select over a paginated list is the
page-boundary problem this work set out to remove.

`listFrom` in `components/operation-form.tsx:24` accepts a bare array *and* an envelope, which is
why the new routes needed no change to how `LookupInput` reads a response.

**`lib/api/query-definitions.ts` did need a change**, which the route table above does not suggest.
`queries` is built from every `GET` endpoint in the catalog, so each of the four new routes became a
user-facing query page on `/queries` and inherited its English `description` as a title — which
`query-definitions.test.ts:17` rejects, since it requires an Arabic title on every query. Each route
therefore also got an Arabic title ("كل مستويات السرية", "كل المؤسسات", "كل الأدوار", "كل أنواع
الملفات", matching the existing `projectTypes.all` and `fileCategories.all`) and a `params` entry
listing the filters its controller accepts.
Add them to `queryParams` only if you intend to filter a table by `parentId` or `isActive`.

## Field-by-field: what becomes a dropdown

Every row is a field that is a free-text input today. The "Key" column is the catalog key to read.

| Field | Where it is now | Key | Change | State |
|---|---|---|---|---|
| `dataCompletionStatus` | `project` field list | `dataCompletionStatus` | Text input → select. **Was a silent-failure risk**: a typo reached a Java enum and failed at request time after the form was filled. | done |
| `status` (user) | `users.update` | `userStatus` | Text input → select. Same risk. | done |
| `type` (organization) | `organizations.create` | `organizationType` | Text input → select. Same risk. **This is the field the report was about**: an organization could not be created without typing `COMPANY` by hand. | done |
| `sourceType` (migration source) | `migrationSources.create`, `.update` | `migrationSourceType` | Text input → select. **Also a backend behaviour change**, see below. | done |
| `status` (batch completion) | `migrationBatches.complete` | `migrationBatchCompletionStatus` | Text input → select. Offer **only** the three terminal states. Offering `PLANNED` or `RUNNING` returns 400. | done |
| `status` (project filter) | `query-definitions.ts` | `projectStatus` | Filter input → select | todo |
| `sourceType` (project filter) | `query-definitions.ts` | `projectSourceType` | Filter input → select | todo |
| `status` (package filter) | `query-definitions.ts` | `packageStatus` | Filter input → select | todo |
| `status` (file filter) | `query-definitions.ts` | `fileStatus` | Filter input → select | todo |
| `status` (user filter) | `query-definitions.ts` | `userStatus` | Filter input → select | todo |
| `type` (organization filter) | `query-definitions.ts` | `organizationType` | Filter input → select | todo |
| `action` (audit filter) | `query-definitions.ts` | `auditAction` | Filter input → select | todo |
| `entityType` (audit filter) | `query-definitions.ts` | `auditEntityType` | Filter input → select | todo |
| `sortBy` | `query-definitions.ts` | `projectSortFields` | Text input → select. Values outside the list are **not** rejected; the server silently sorts by `createdAt`, so the form is the only place this is visible. | todo |
| `sortDirection`, `order` | `query-definitions.ts` | `sortDirections` | Text input → select. `asc` / `desc` — anything else is read as `desc`. | todo |
| `isActive`, `isLatestVersion` | every query | — | Text input → `true`/`false` select, or a checkbox. | todo |
| `page`, `size`, `limit`, `year` | every query | — | At minimum `type="number"`. | todo |

The line numbers from the original draft have been dropped: they had already moved by the time the
row was acted on, and a stale line number is worse than none. The field is named instead, which does
not go out of date.

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
  client that never heard of them — its hardcoded values and its paginated lookups alike — keeps
  working unchanged, because the paginated siblings are still there and still paginated.
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

The five body fields in the table are **done**; the filter rows are not. What was built:

1. `lib/api/catalog.ts` — done: `enums.list` → `GET /api/v1/enums`. The gateway is driven entirely
   from this allow-list, so this one line is what makes the route reachable from the browser.
2. `lib/api/enum-options.ts` — new. `enumOptions(response, enumKey, current)` does the reading,
   the labelling and the stale-value handling as pure functions, so it is testable without a DOM.
   Deliberately *not* a Zod schema over the response: the catalog is additive, so a schema
   asserting a fixed key set would break on a key the backend adds, which is the opposite of what
   is wanted. An unknown key renders an empty list and says so, rather than failing the build.
3. `lib/api/operation-definitions.ts` — done, with `enumKey?: string` on `OperationField` and a
   `FieldKind` of `"enum"`, not `choices?: string[]` and `"select"`. **Carrying a key rather than a
   list is the whole point**: a list copied into the client is a copy that drifts, and the drift is
   invisible until someone picks a value the server has retired. `choice()` fills these in for
   `organizations.create` `type`, `users.update` `status`, `projects.*` `dataCompletionStatus`,
   `migrationSources.*` `sourceType`, and `migrationBatches.complete` `status`.
4. `components/operation-form.tsx` — done: `EnumInput` renders the `<select>`, sharing one
   `["enums", "values"]` query across every such field on the page. Labels live in
   `enum-options.ts`, not here, so they are testable.
5. `lib/api/query-definitions.ts` — **not done.** `queryParams` is still `string[]`, so the filter
   rows have nowhere to carry options. Widen it to `QueryParam[]` with an optional `key`; the
   `queryParams.includes(name)` check in `components/query-form.tsx:54` then needs the name
   property. `enums.list` also picked up a query page of its own, "الخيارات الثابتة للحقول", for
   consistency with the other `*.all` catalogs.
6. `components/query-form.tsx:54` — **not done**, follows from 5.
7. `settings/migration/page.tsx` — nothing to do. The `confidentialityLevels.list` lookup it used to
   carry was the input for `defaultConfidentialityCode`, and that field is gone with the setting
   behind it.

### Two decisions in the built version worth knowing

- **An unrecognised constant is still offered, shown in English.** `ENUM_LABELS` is display-only and
  falls back to the raw value. Refusing an unmapped value would put the client back in charge of
  the set, which is the exact bug this work removed. A missing label costs a reader one unfamiliar
  word, not the ability to choose the value at all.
- **A value the catalog no longer offers stays selectable and is prepended.** Dropping it would make
  an edit form submit a different value than the record holds — a silent data change on a save that
  was for something else entirely. The reader sees the raw English value, which is the clue that it
  is no longer on offer.

## Verification

`postman/06_Enums.postman_collection.json` is new and asserts every key is present, non-empty, and
duplicate-free, and that the completion set excludes the non-terminal states.

`postman/04_Import_and_Migration.postman_collection.json` gained a negative case:
`Create Migration Source with an unsupported type` expects 400 on `"sourceType": "ORACLE"` — the
exact value the old `MigrationSourceRequest` documented as its example.

`GlobalExceptionHandlerTest` covers the error wording at the handler level, including the property
that a constraint the caller never touched must not leak its name: the response is a plain Arabic
sentence with no field and no constraint name, because `created_by` is set by the server and naming
a field the caller did not send would be a worse answer than naming none.

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

Full suite: **264 tests, 0 failures, 0 errors** against real PostgreSQL and MinIO.

On the frontend side of the selection lists, `CENTRAL-ARCHIVE-FRONTEND-TEST` is at
**43 tests passing** and a clean `next build` with type checking. The test that caught the one thing
the route table does not imply is `query-definitions.test.ts` — *"shows Arabic titles for every
query"* — which fails the moment a `GET` endpoint is added to `catalog.ts` without a title in
`query-definitions.ts`. Adding a route to the catalog is therefore a two-file change, not one. That
is exactly what happened when `enums.list` was added: the test failed on the new key, and the title
"الخيارات الثابتة للحقول" is the fix rather than an exclusion.

## The five body dropdowns, as verified

The form dropdowns are done and were checked end to end rather than by type checking, because the
failure this work fixes is invisible to the compiler: a field of the right shape, rendering the
right number of options, sourced from the wrong place, or from a payload that never arrives.

- **The catalog answers the five keys the forms ask for**, read through the gateway exactly as the
  browser calls it (`/api/gateway/enums.list`), after a real login:
  `organizationType = COMPANY, FACTORY, LINE, DEPARTMENT` · `userStatus = ACTIVE, DISABLED, LOCKED` ·
  `dataCompletionStatus = COMPLETE, INCOMPLETE` · `migrationSourceType = POSTGRESQL, LEGACY_DB` ·
  `migrationBatchCompletionStatus = SUCCESS, PARTIAL, FAILED`. The completion set excludes
  `PLANNED` and `RUNNING` as intended.
- **The reported case works in the browser.** `organizations.create` now offers شركة / مصنع /
  خط إنتاج / إدارة, and submitting the form without typing a type created the organization with
  `type = COMPANY` and the Arabic name intact in PostgreSQL — 25 characters, 47 bytes,
  byte-exact on read-back.
- **`enum-options.test.ts`** (15 cases) covers what the rendering cannot: a set that is absent, a set
  that is not a list, a payload that has not arrived, a set holding numbers. All four used to be an
  empty dropdown with no explanation, and none of them may throw — the caller shows a loading or
  failure message, and a thrown error would blank the whole form.
- **Two tests in `operation-definitions.test.ts`** keep the mapping honest: every field of kind
  `enum` must carry an `enumKey` (it cannot render otherwise), and every `enumKey` must be one the
  server actually publishes (it would render empty otherwise). The published set is named in the
  test, so a key renamed on the server fails here rather than in a form.
- Frontend suite: **43 tests, 0 failures**, `tsc --noEmit` clean, `eslint` 0 errors, `next build`
  compiles.

### A note on the two Jackson versions, for whoever touches this next

`tools.jackson.core:jackson-databind` (what Spring's HTTP converter throws) and
`com.fasterxml.jackson.core:jackson-databind` (what the app autowires as `ObjectMapper`) are both
on the classpath. An exception handler written against the wrong one compiles cleanly and never
fires. That is not hypothetical: it is why `ArabicDataAndFieldErrorsHttpIntegrationTest` is an HTTP
test and not a unit test, and it is the same reason the enum work above was verified through the
running gateway rather than trusted to `tsc`.

