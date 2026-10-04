# Project delete and the contract changes around it — frontend handoff

Backend branch: `feat/legacy-migration-settings` · the code changes are in `6a3f292`, this file and
its verification script are in the commit that follows it.
Companion: `FRONTEND-ENUMS-HANDOFF.md`, which covers `/api/v1/enums` and the general error envelope.

Every response quoted below was captured from the running server, not read off the source. The
script that produced them is in this repo root — run it rather than trust the paste.

---

## 1. `DELETE /api/v1/projects/{id}` — a project created by mistake

```
DELETE /api/v1/projects/{id}
```

No request body. No query parameters. The path variable is a UUID.

**Allowed roles:** `system_admin`, `central_data_entry`. Anything else gets `403 ACCESS_DENIED`,
so the control should not be offered to the other roles at all — the server refuses regardless, but
a button that always fails is worse than no button.

**The row is really gone.** This is a hard delete, not `is_active = false`: there is no soft-delete
column on the project table, and there could not be one without a schema decision. What survives is
the audit row, which is write-once (the schema blocks deleting it), so "this project existed and was
removed on this date by this person" is still answerable afterwards.

**Success — `200`:**

```json
{ "success": true, "meta": { "message": "تم حذف المشروع" } }
```

There is no `data` body. `data` is absent, not null — a client doing `response.data.id` gets
`undefined`, which is correct here.

**Unknown id — `404`**, same shape as every other missing record in the API:

```json
{ "success": false,
  "data": { "code": "NOT_FOUND", "message": "المشروع غير موجود", "details": null },
  "meta": { "message": "المشروع غير موجود" } }
```

## 2. A refusal is `422`, not `404`, and it names the rule

This is the part the UI has to design for. A project that has left the draft state still exists, so
answering `404` would tell the reader the record is gone when it is on screen in front of them. So
anything that has moved past "a stray keystroke" answers **`422 BUSINESS_RULE_VIOLATION`** with an
Arabic sentence that says what to do about it:

```json
{ "success": false,
  "data": { "code": "BUSINESS_RULE_VIOLATION",
            "message": "لا يمكن حذف مشروع حالته معتمد (APPROVED). الحذف متاح فقط لمشروع ما زال مسودة لم يُرسل للمراجعة. إن كان خطأ في بيانات مشروع زُخِمت بالفعل، فراجع الحالة المتاحة له بدل الحذف",
            "details": null },
  "meta": { "message": "…same sentence…" } }
```

The five rules, in the order the service checks them. Only one fires per attempt:

| Refused because | Message (verbatim) |
|---|---|
| Status is not `DRAFT` | `لا يمكن حذف مشروع حالته <label> (<NAME>). الحذف متاح فقط لمشروع ما زال مسودة لم يُرسل للمراجعة. إن كان خطأ في بيانات مشروع زُخِمت بالفعل، فراجع الحالة المتاحة له بدل الحذف` |
| The project has files | `لا يمكن حذف مشروع عليه ملفات (<n>). الملفات سجل محفوظ ولا تُحذف مع المشروع` |
| The project has members | `لا يمكن حذف مشروع له أعضاء. أزل الأعضاء أولاً` |
| It is the base of a version chain | `لا يمكن حذف مشروع لأنه أساس لإصدارات لاحقة منه` |
| A package links to it | `لا يمكن حذف مشروع مرتبط بحزمة واردة من الترحيل` |

Two things to note about the status sentence. The Arabic word and the stored enum name are **both**
in it (`معتمد (APPROVED)`), because someone looking at the row sees the English in the database and
someone reading the message needs the Arabic; and the number in the files message is the real count,
so it can be shown as a number rather than a "has files" boolean.

**These refusals are meant to be shown, not parsed.** There is no machine-readable reason code
beyond `BUSINESS_RULE_VIOLATION`, and adding one would mean deciding which of the five a client
branches on. If the UI needs to branch — say, to offer "remove the members first" as a button — say
so and it should be a field on the response, not a `message.includes("أعضاء")`.

The two rules that are currently unreachable: no project in the database is a version-chain base or a
package's target, because `BranchPackageService` moves a project to `IMPORTED` on arrival, and
`IMPORTED` is refused by the first rule. Unit tests cover them. Do not build a UI affordance for
them on the assumption they are common.

## 3. Members are `snake_case`, and that is not a bug to work around

`MemberCreateRequest` and `MemberUpdateRequest` are the only request records in this API that name
their fields `snake_case`, and the names are `@JsonProperty` **renames**, not aliases:

| Send this | Not this |
|---|---|
| `person_name` | `personName` |
| `organization_name` | `organizationName` |
| `role_name` | `roleName` |
| `notes` | `notes` |

Sending the camelCase name is not rejected as an unknown field — Jackson drops it, the field arrives
`null`, and validation then reports a required field as missing. Verified on the running server:

- `{"personName": "أ.ب"}` → `400` `اسم عضو المشروع مطلوب`
- `{"person_name": "أ.ب"}` → `201`

The API keeps these names because the Postman collections and any other client already speak them,
and renaming the API to match a new client's habits would break those. `notes` is camel-neutral and
works either way. `MemberRequest.java` is dead code — no controller accepts it — so ignore it if you
find it.

## 4. Page size: `size` and `limit` both work, on the routes that used only one

`/users` and `/roles` historically read `limit`; most routes added since read `size`. Spring discards
a query parameter it does not recognise, so asking for the wrong name got you the default page of 20
and an ordinary `200` — a page smaller than asked for, with nothing indicating it. Both names are now
accepted on those two routes.

- `size` wins if both are sent.
- Both clamp to a maximum of **100**. `size=500` returns 100 rows and reports `limit: 100`.
- The clamped value comes back in the envelope, so a client can read what it actually got.

`GET /dashboard/activity` was left alone: it reads `limit` only, clamps it to 50 in the service, returns
a **plain array** under `data` with no `total` and no `meta`, and so has no paging envelope to read a
clamped size back from. See gap 6 for why its rows are not interchangeable with the audit log's.

## 5. Validation errors name the field, in the name you sent

Covered fully in `FRONTEND-ENUMS-HANDOFF.md`; the two points that matter for these forms:

- With **one** bad field, `data.message` *is* that field's sentence. With several, it is
  `الحقول التالية غير صالحة (<n>)` and `data.details[]` carries them, each with
  `{ "field": …, "message": … }`.
- `details[].field` is the **request field name as you sent it** — so for a member it is
  `person_name`, not `personName`, and for a nested member it arrives as the path Jackson reports,
  e.g. `members[1].role_name`. Bind on the name you sent, not on a field's Java property.

## 6. `/api/v1/audit-logs` works now

It returned `500` for **every** query before this change — Hibernate 6 binds a parameter twice and
the resulting untyped `? is null` was rejected by PostgreSQL. The filters now work, so the audit
history behind a record is something a screen can actually show. `system_admin` only.

After the delete above, `?entityType=Project&entityId=<id>` returns:

```
READ    Status: FAILURE | Error: Project not found with id: c6858fbd-…      ← reading it after the delete
DELETE  Status: SUCCESS | Duration: 31ms
DELETE  Status: FAILURE | Duration: 16ms | Error: لا يمكن حذف مشروع له أعضاء. أزل الأعضاء أولاً
CREATE  Status: SUCCESS | Duration: 23ms
```

`entityType` is `Project` and `action` is `DELETE` / `CREATE` / `READ`, all of which
`GET /api/v1/enums` publishes under `auditEntityType` and `auditAction`.

**Two caveats, both real:**

1. **There is no `status` field on the row.** Success and failure are packed into `description` as
   text. So a screen showing "deleted" versus "refused" has to read that string, which is fragile. If
   you need it as data, the backend should add `status` and `errorMessage` columns — say so and it is
   a small change.
2. **`description` mixes languages.** The refusal above is Arabic; the `404` one is
   `Project not found with id: …` in English, because the aspect logs the raw exception message while
   the response layer translates it. Anything rendering audit text has to cope with both.

## 7. Status labels are **not** in the API — and the two copies already disagree

`ProjectStatus` gained `label()` and `describe()` in Java, and `describe()` is what appears inside the
refusal messages above. But neither is exposed: `GET /api/v1/enums` returns **names only**, and
`ProjectResponse.status` is the bare enum name. Confirmed live — no `label` key anywhere in the
catalog response.

So the client keeps its own display map, and it has drifted from the server in two places:

| Constant | Backend `label()` | Frontend `ENUM_LABELS` |
|---|---|---|
| `IMPORTED` | مستورد | مُرحَّل |
| `NEEDS_CORRECTION` | يحتاج تصحيحاً | يحتاج تصحيح |

A reader can therefore see two different Arabic words for the same status depending on whether it
came from a list or from a refusal message. Aligning the frontend map is the cheap fix; the durable
one is exposing labels in `/api/v1/enums` (or a `statusLabel` on `ProjectResponse`), which deletes the
duplicate map entirely. That is a backend change — request it and it is small.

---

## Calling it through the gateway

The Next.js gateway resolves a catalog key to one method and path, and **requires the HTTP method to
match the catalog entry** — a mismatch is `404` `العملية غير مسموحة.` and never reaches the backend.

```
DELETE /api/gateway/projects.delete?param=id=<uuid>
```

Path variables travel as `param=<name>=<value>`, not as path segments — the gateway interpolates them
into the catalog path. Every other query parameter is passed through untouched.

Catalog entry to add:

```ts
{ key: "projects.delete", method: "DELETE", path: "/api/v1/projects/:id", description: "…" }
```

## Verifying it yourself

```bash
node handoff-contract.mjs      # from the repo root, with the backend on :8080
```

It signs in as `admin` / `admin123` (the seeded password), creates a `DRAFT` under a fresh project
number, adds a member to force the `422`, removes the member, deletes, reads the deleted id, tries an
`APPROVED` project and a nonexistent id, then prints the audit rows. Everything it creates it removes,
and it is re-runnable — run it as many times as you like and the database is left as it was.

## Known gaps — design around these, they are not fixed

1. **`size=0` is accepted silently.** `/users?size=0` returns one row and reports `limit: 1`. The
   maximum of 100 is enforced; the minimum is not, and no route rejects a nonsensical page size.
2. **Bad credentials return `500`, not `401`.** An unknown username and a wrong password both answer
   `500 INTERNAL_ERROR` with `حدث خطأ غير متوقع في الخادم`, so the login screen cannot tell "wrong
   password" from "server is down" from the status alone.
3. **Legacy uppercase roles grant nothing.** `core.roles` holds `ADMIN`, `IMPORT_OFFICER`, `REVIEWER`
   and `USER` beside their lowercase twins, while every `hasAnyRole` in the code names the lowercase
   form. No account currently depends on the uppercase ones, but an account migrated in holding only
   `IMPORT_OFFICER` would be refused the import endpoints.
4. **The new endpoint is not in any Postman collection.** None of the six collections in this repo
   has a `DELETE /projects` request.
5. **List routes are not sorted by anything the API promises.** `GET /projects` returns rows in an
   order that is not stable across calls, and returns 100 of whatever the caller may see — on this
   database 102 of the 151 rows, because confidentiality filtering hides the rest. There is no "load
   more" and no server-side search except `/projects/search?searchTerm=` (which matches project
   number, prefix or Arabic name) and `/users?search=`. A picker that lists from a single page will
   show the reader that a record they know by name does not exist.
6. **`/dashboard/activity` speaks a different vocabulary from `/audit-logs`.** Its `action` values are
   `FILE_UPLOADED`, `PROJECT_APPROVED`, `PROJECT_IMPORTED`, `PACKAGE_IMPORTED` and its `entityType`
   values include `PACKAGE` — none of which appear in the `auditAction` or `auditEntityType` lists
   that `/api/v1/enums` publishes for the audit log, where the same events are `CREATE` / `UPDATE` /
   `IMPORT` on `File` / `Project` / `BranchPackage`. A filter control shared by both screens will match
   nothing on one of them.