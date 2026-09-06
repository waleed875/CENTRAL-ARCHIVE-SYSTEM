# Central Platform MVP - API Endpoints Design

## Base Configuration

```
Base URL: /api/v1
Content-Type: application/json
Authentication: Bearer JWT (HTTP 401 for unauthorized)
Authorization: Role-based (HTTP 403 for forbidden)
Pagination: ?page=1&limit=20 (default 20, max 100)
Sorting: ?sort=created_at&order=desc
Filtering: Field-based query parameters
```

## Common HTTP Status Codes

| Code | Usage |
|------|-------|
| 200 | Success (GET, PATCH) |
| 201 | Created (POST) |
| 204 | No Content (DELETE) |
| 400 | Validation error |
| 401 | Unauthenticated |
| 403 | Forbidden |
| 404 | Not found |
| 409 | Conflict (duplicate, constraint violation) |
| 422 | Unprocessable entity (business logic error) |

## Response Envelope

```json
{
  "success": true,
  "data": {},
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 150,
    "totalPages": 8
  }
}
```

## Error Response

```json
{
  "success": false,
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Project number already exists",
    "details": [
      { "field": "project_number", "message": "Must be unique per organization/year" }
    ]
  }
}
```

---

## 1. Authentication

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| POST | `/auth/login` | Login and receive JWT | No |
| POST | `/auth/logout` | Invalidate current token | Yes |
| POST | `/auth/refresh` | Refresh expired token | Yes |
| GET | `/auth/me` | Get current user profile | Yes |
| PATCH | `/auth/change-password` | Change own password | Yes |

### POST /auth/login

```
Request:  { "username": "string", "password": "string" }
Response: {
  "token": "jwt_string",
  "expires_in": 3600,
  "user": {
    "id": "uuid",
    "username": "string",
    "full_name": "string",
    "organization": { "id": "uuid", "name": "string" },
    "roles": [{ "code": "system_admin", "name": "مدير النظام" }],
    "max_confidentiality_level": { "id": "uuid", "level_order": 3 }
  }
}
```

### POST /auth/logout

```
Response: 204 No Content
```

### POST /auth/refresh

```
Response: { "token": "new_jwt_string", "expires_in": 3600 }
```

### GET /auth/me

```
Response: { user object with roles, organization, confidentiality_level }
```

### PATCH /auth/change-password

```
Request:  { "current_password": "string", "new_password": "string" }
Response: 200 OK
```

---

## 2. Organizations (Tree Structure)

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| GET | `/organizations` | List organizations | Yes |
| GET | `/organizations/tree` | Get full tree | Yes |
| POST | `/organizations` | Create organization | system_admin |
| GET | `/organizations/:id` | Get one organization | Yes |
| GET | `/organizations/:id/tree` | Get subtree from node | Yes |
| GET | `/organizations/:id/path` | Get path from root | Yes |
| PATCH | `/organizations/:id` | Update organization | system_admin |
| DELETE | `/organizations/:id` | Deactivate organization | system_admin |

### GET /organizations

```
Query: ?parent_id=uuid&type=DEPARTMENT&is_active=true&search=lab&page=1&limit=50

Response: [{
  "id": "uuid",
  "parent_id": "uuid|null",
  "name": "Quality Lab",
  "code": "QL-01",
  "type": "DEPARTMENT",
  "description": "...",
  "is_active": true,
  "children_count": 0,
  "projects_count": 12,
  "created_at": "2025-01-01T00:00:00Z"
}]
```

### GET /organizations/tree

```
Query: ?root_id=uuid&max_depth=4

Response: [{
  "id": "uuid",
  "name": "Main Company",
  "type": "COMPANY",
  "children": [
    {
      "id": "uuid",
      "name": "Factory A",
      "type": "FACTORY",
      "children": [...]
    }
  ]
}]
```

### POST /organizations

```
Request: {
  "parent_id": "uuid|null",
  "name": "string",
  "code": "string",
  "type": "COMPANY|FACTORY|LINE|DEPARTMENT",
  "description": "string?"
}

Response: 201 Created { organization object }
Errors: 409 if code already exists
```

### GET /organizations/:id

```
Query: ?include=children,parent,path

Response: {
  "id": "uuid",
  "parent_id": "uuid",
  "name": "string",
  "code": "string",
  "type": "DEPARTMENT",
  "description": "string",
  "is_active": true,
  "parent": { "id": "uuid", "name": "string" },
  "children": [],
  "path": "Main Company > Factory A > Line B > Department C",
  "created_at": "2025-01-01T00:00:00Z"
}
```

### PATCH /organizations/:id

```
Request: { "name": "string?", "description": "string?", "is_active": boolean? }
Response: 200 OK
Errors: 409 if deactivating would orphan children/projects
```

### DELETE /organizations/:id

```
Response: 204 No Content (soft deactivation)
Errors: 422 if has active children or active projects
```

---

## 3. Users

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| GET | `/users` | List users | Yes |
| POST | `/users` | Create user | system_admin |
| GET | `/users/:id` | Get one user | Yes |
| PATCH | `/users/:id` | Update user | system_admin |
| DELETE | `/users/:id` | Deactivate user | system_admin |
| GET | `/users/:id/roles` | List user roles | Yes |
| POST | `/users/:id/roles` | Assign role | system_admin |
| DELETE | `/users/:id/roles/:roleId` | Remove role | system_admin |

### GET /users

```
Query: ?organization_id=uuid&status=ACTIVE&role=auditor&search=ahmed&page=1&limit=20

Response: [{
  "id": "uuid",
  "username": "ahmed_k",
  "full_name": "Ahmed Khalil",
  "email": "ahmed@example.com",
  "status": "ACTIVE",
  "organization": { "id": "uuid", "name": "Quality Lab", "code": "QL-01" },
  "max_confidentiality_level": { "id": "uuid", "name": "Secret", "level_order": 3 },
  "roles": [{ "id": "uuid", "code": "auditor", "name": "مدقق" }],
  "created_at": "2025-01-01T00:00:00Z"
}]
```

### POST /users

```
Request: {
  "username": "string (unique)",
  "password": "string (min 8 chars)",
  "full_name": "string",
  "email": "string? (unique)",
  "organization_id": "uuid?",
  "max_confidentiality_level_id": "uuid?",
  "role_ids": ["uuid"]
}

Response: 201 Created { user object (no password_hash) }
Errors: 409 if username or email already exists
```

### GET /users/:id

```
Query: ?include=roles,organization

Response: {
  "id": "uuid",
  "username": "string",
  "full_name": "string",
  "email": "string",
  "organization": { ... },
  "max_confidentiality_level": { ... },
  "roles": [...],
  "status": "ACTIVE",
  "created_at": "...",
  "updated_at": "..."
}
```

### PATCH /users/:id

```
Request: {
  "full_name": "string?",
  "email": "string?",
  "organization_id": "uuid?",
  "max_confidentiality_level_id": "uuid?",
  "status": "ACTIVE|DISABLED|LOCKED?"
}

Response: 200 OK
Errors: 409 if email already exists
```

### DELETE /users/:id

```
Response: 204 No Content (sets status to DISABLED)
Errors: 422 if user has active import operations
```

### GET /users/:id/roles

```
Response: [{
  "id": "uuid",
  "code": "system_admin",
  "name": "مدير النظام",
  "assigned_at": "2025-01-01T00:00:00Z",
  "assigned_by": { "id": "uuid", "full_name": "string" }
}]
```

### POST /users/:id/roles

```
Request: { "role_id": "uuid" }
Response: 201 Created
Errors: 409 if role already assigned
```

### DELETE /users/:id/roles/:roleId

```
Response: 204 No Content
```

---

## 4. Roles

النظام المركزي يعتمد فقط على الأدوار المركزية السبعة التالية:
| الدور البرمجي | الاسم العربي | الوظيفة الأساسية |
|---|---|---|
| `system_admin` | مدير النظام | إدارة المستخدمين والأدوار والجهات والنظام والصلاحيات الحساسة |
| `central_data_entry` | مدخل بيانات مركزي | إنشاء المشاريع وتعديلها وإضافة الملفات من داخل المركز |
| `auditor` | مدقق | مراجعة المشاريع والملفات ثم الاعتماد أو طلب الاستكمال/الرفض |
| `archive_officer` | مسؤول أرشفة | تنفيذ وإدارة عمليات أرشفة المشاريع |
| `import_officer` | مسؤول استيراد | استيراد الحزم إلى النظام المركزي |
| `export_officer` | مسؤول تصدير | تصدير المشاريع والملفات والحزم |
| `read_only` | مستخدم قراءة فقط | استعراض البيانات المسموح بها بدون تعديل |

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| GET | `/roles` | List all roles | Yes |
| POST | `/roles` | Create role | system_admin |
| GET | `/roles/:id` | Get one role | Yes |
| PATCH | `/roles/:id` | Update role | system_admin |
| DELETE | `/roles/:id` | Deactivate role | system_admin |

### GET /roles

```
Query: ?is_active=true&search=admin

Response: [{
  "id": "uuid",
  "code": "system_admin",
  "name": "مدير النظام",
  "description": "إدارة المستخدمين والأدوار والجهات والنظام والصلاحيات الحساسة",
  "is_active": true,
  "users_count": 3
}]
```

### POST /roles

```
Request: {
  "code": "string (unique, e.g. system_admin, central_data_entry, auditor, archive_officer, import_officer, export_officer, read_only)",
  "name": "string",
  "description": "string?"
}

Response: 201 Created
Errors: 409 if code already exists
```

### GET /roles/:id

```
Response: { role object with users_count and assigned_users }
```

### PATCH /roles/:id

```
Request: { "name": "string?", "description": "string?", "is_active": boolean? }
Response: 200 OK
```

### DELETE /roles/:id

```
Response: 204 No Content (soft deactivation)
Errors: 422 if role currently assigned to active users
```

---

## 5. Confidentiality Levels

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| GET | `/confidentiality-levels` | List all levels | Yes |
| POST | `/confidentiality-levels` | Create level | system_admin |
| GET | `/confidentiality-levels/:id` | Get one level | Yes |
| PATCH | `/confidentiality-levels/:id` | Update level | system_admin |
| DELETE | `/confidentiality-levels/:id` | Delete level | system_admin |

### GET /confidentiality-levels

```
Response: [{
  "id": "uuid",
  "code": "SECRET",
  "name": "Secret",
  "level_order": 3,
  "description": "Secret classification",
  "projects_count": 15,
  "files_count": 42,
  "users_count": 5
}]
```

### POST /confidentiality-levels

```
Request: {
  "code": "string (unique)",
  "name": "string",
  "level_order": "integer (unique, > 0)",
  "description": "string?"
}

Response: 201 Created
Errors: 409 if code or level_order already exists
```

### GET /confidentiality-levels/:id

```
Response: { level object with counts }
```

### PATCH /confidentiality-levels/:id

```
Request: { "name": "string?", "level_order": number?, "description": "string?" }
Response: 200 OK
Errors: 409 if level_order conflicts
```

### DELETE /confidentiality-levels/:id

```
Response: 204 No Content
Errors: 422 if used by any projects, files, or users
```

---

## 6. Project Types

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| GET | `/project-types` | List all types | Yes |
| POST | `/project-types` | Create type | system_admin |
| GET | `/project-types/:id` | Get one type | Yes |
| PATCH | `/project-types/:id` | Update type | system_admin |
| DELETE | `/project-types/:id` | Deactivate type | system_admin |

### GET /project-types

```
Query: ?is_active=true

Response: [{
  "id": "uuid",
  "code": "INFRASTRUCTURE",
  "name": "Infrastructure Project",
  "description": "...",
  "is_active": true,
  "projects_count": 24
}]
```

### POST /project-types

```
Request: { "code": "string (unique)", "name": "string", "description": "string?" }
Response: 201 Created
```

### GET /project-types/:id

```
Response: { type object with projects_count }
```

### PATCH /project-types/:id

```
Request: { "name": "string?", "description": "string?", "is_active": boolean? }
Response: 200 OK
```

### DELETE /project-types/:id

```
Response: 204 No Content (soft deactivation)
```

---

## 7. Projects (Core Entity with Versioning)

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| GET | `/projects` | List projects | Yes |
| POST | `/projects` | Create project | central_data_entry+ |
| GET | `/projects/:id` | Get one project | Yes |
| PATCH | `/projects/:id` | Update project | central_data_entry+ |
| POST | `/projects/:id/new-version` | Create new version | central_data_entry+ |
| GET | `/projects/:id/versions` | List all versions | Yes |
| PATCH | `/projects/:id/submit-for-review` | Submit for review | central_data_entry+ |
| PATCH | `/projects/:id/request-correction` | Request correction | auditor+ |
| PATCH | `/projects/:id/approve` | Approve project | auditor+ |
| PATCH | `/projects/:id/archive` | Archive project | system_admin, archive_officer |
| GET | `/projects/search` | Full-text search | Yes |
| GET | `/projects/stats` | Dashboard statistics | Yes |

### GET /projects

```
Query:
  ?organization_id=uuid
  &project_type_id=uuid
  &confidentiality_level_id=uuid
  &status=DRAFT|IMPORTED|UNDER_REVIEW|NEEDS_CORRECTION|APPROVED|ARCHIVED
  &source_type=MANUAL|BRANCH_PACKAGE|MIGRATION
  &year=2025
  &project_uid=uuid (all versions of same project)
  &is_latest_version=true (default)
  &created_by=uuid
  &approved_by=uuid
  &search=lab+equipment
  &date_from=2025-01-01
  &date_to=2025-12-31
  &sort=created_at&order=desc
  &page=1&limit=20

Response: [{
  "id": "uuid",
  "project_uid": "uuid",
  "version_no": 1,
  "is_latest_version": true,
  "project_number": "PRJ-2025-001",
  "project_code": "QC-01",
  "name": "Quality Lab Equipment Upgrade",
  "year": 2025,
  "status": "APPROVED",
  "data_completion_status": "COMPLETE",
  "source_type": "MANUAL",
  "organization": { "id": "uuid", "name": "Quality Lab", "code": "QL-01" },
  "project_type": { "id": "uuid", "name": "Infrastructure" },
  "confidentiality_level": { "id": "uuid", "name": "Internal", "level_order": 2 },
  "executing_entity": "ABC Contractors",
  "supervisor_name": "Dr. Ahmed",
  "files_count": 12,
  "members_count": 4,
  "created_by": { "id": "uuid", "full_name": "Admin" },
  "approved_by": { "id": "uuid", "full_name": "Dr. Sara" },
  "approved_at": "2025-06-15T10:00:00Z",
  "created_at": "2025-01-15T08:00:00Z",
  "updated_at": "2025-06-15T10:00:00Z"
}]
```

### POST /projects

```
Request: {
  "organization_id": "uuid (required)",
  "project_type_id": "uuid?",
  "confidentiality_level_id": "uuid?",
  "project_number": "string (required)",
  "project_code": "string?",
  "name": "string (required)",
  "year": "integer >= 1900 (required)",
  "executing_entity": "string?",
  "supervisor_name": "string?",
  "description": "string?",
  "notes": "string?",
  "table_of_contents": "string?",
  "keywords": "string?",
  "members": [{
    "person_name": "string",
    "organization_name": "string?",
    "role_name": "string?",
    "notes": "string?"
  }]
}

Response: 201 Created {
  id: "uuid",
  project_uid: "uuid (new)",
  version_no: 1,
  is_latest_version: true,
  status: "DRAFT",
  ... full project object
}

Errors:
  409 - project_number already exists for this organization/year
  422 - organization_id not found, invalid year, etc.
```

### GET /projects/:id

```
Query: ?include=members,files,versions,organization,type,confidentiality,created_by,approved_by

Response: {
  id, project_uid, version_no, is_latest_version, previous_project_id,
  project_number, project_code, name, year,
  status, data_completion_status, review_notes,
  source_type, source_reference,
  organization: { id, name, code, type },
  project_type: { id, code, name },
  confidentiality_level: { id, code, name, level_order },
  executing_entity, supervisor_name,
  description, notes, table_of_contents, keywords,
  created_by: { id, full_name },
  approved_by: { id, full_name },
  approved_at, created_at, updated_at,
  members: [{ id, person_name, organization_name, role_name, notes }],
  files: [{ id, original_name, category, type, size_bytes, status, ... }],
  versions: [{ id, version_no, status, created_at, created_by }]
}
```

### PATCH /projects/:id

```
Request (all fields optional, depends on current status):
  DRAFT / NEEDS_CORRECTION: all metadata fields editable
  UNDER_REVIEW: limited to notes, description
  APPROVED / ARCHIVED: read-only, must use new-version endpoint

  {
    "organization_id": "uuid?",
    "project_type_id": "uuid?",
    "confidentiality_level_id": "uuid?",
    "project_number": "string?",
    "project_code": "string?",
    "name": "string?",
    "year": number?,
    "executing_entity": "string?",
    "supervisor_name": "string?",
    "description": "string?",
    "notes": "string?",
    "table_of_contents": "string?",
    "keywords": "string?",
    "data_completion_status": "COMPLETE|INCOMPLETE?"
  }

Response: 200 OK { updated project object }
Errors: 422 if status does not allow editing
```

### POST /projects/:id/new-version

Creates a new version of an APPROVED or ARCHIVED project.

```
Request: {
  "name": "string? (defaults to current)",
  "description": "string? (defaults to current)",
  "notes": "string?",
  ... any fields to override from previous version
}

Response: 201 Created {
  id: "new-uuid",
  project_uid: "same-uid",
  version_no: 2,
  is_latest_version: true,
  status: "DRAFT",
  previous_project_id: "original-uuid",
  ... full new version object
}

Logic:
  1. Copy all metadata from current version
  2. Apply overrides from request
  3. Set version_no = max(existing_versions) + 1
  4. Set previous_project_id = current version id
  5. Set is_latest_version = true on new, false on old
  6. Copy members from previous version
  7. Do NOT copy files (new version gets new files)
  8. Status starts as DRAFT
```

### GET /projects/:id/versions

```
Response: [{
  "id": "uuid",
  "version_no": 1,
  "status": "ARCHIVED",
  "is_latest_version": false,
  "created_by": { "id": "uuid", "full_name": "string" },
  "approved_by": { "id": "uuid", "full_name": "string" },
  "created_at": "2025-01-15T08:00:00Z"
}, {
  "id": "uuid",
  "version_no": 2,
  "status": "APPROVED",
  "is_latest_version": true,
  "created_by": { ... },
  "approved_by": { ... },
  "created_at": "2025-06-01T10:00:00Z"
}]
```

### PATCH /projects/:id/submit-for-review

```
Request: {} (no body needed)
Response: 200 OK { status: "UNDER_REVIEW" }
Errors: 422 if current status is not DRAFT or NEEDS_CORRECTION
```

### PATCH /projects/:id/request-correction

```
Request: { "review_notes": "string (required)" }
Response: 200 OK { status: "NEEDS_CORRECTION", review_notes: "..." }
Errors: 422 if current status is not UNDER_REVIEW
```

### PATCH /projects/:id/approve

```
Request: {} (no body needed)
Response: 200 OK {
  status: "APPROVED",
  approved_by: { current user },
  approved_at: "now"
}
Errors: 422 if current status is not UNDER_REVIEW
```

### PATCH /projects/:id/archive

```
Request: {} (no body needed)
Response: 200 OK { status: "ARCHIVED" }
Errors: 422 if current status is not APPROVED
```

### GET /projects/search

```
Query: ?q=lab+equipment+2025&year=2025&organization_id=uuid&status=APPROVED&confidentiality_level_id=uuid

Response: [{
  "id": "uuid",
  "project_uid": "uuid",
  "name": "Quality Lab Equipment Upgrade",
  "project_number": "PRJ-2025-001",
  "year": 2025,
  "status": "APPROVED",
  "organization": { "name": "Quality Lab" },
  "relevance_score": 0.95
}]
```

### GET /projects/stats

```
Response: {
  "total": 1250,
  "by_status": {
    "DRAFT": 45,
    "IMPORTED": 120,
    "UNDER_REVIEW": 30,
    "NEEDS_CORRECTION": 15,
    "APPROVED": 800,
    "ARCHIVED": 240
  },
  "by_source_type": {
    "MANUAL": 600,
    "BRANCH_PACKAGE": 400,
    "MIGRATION": 250
  },
  "by_year": { "2025": 180, "2024": 320 },
  "recent_activity": [{
    "id": "uuid",
    "name": "...",
    "status": "UNDER_REVIEW",
    "updated_at": "..."
  }]
}
```

---

## 8. Project Members

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| GET | `/projects/:projectId/members` | List members | Yes |
| POST | `/projects/:projectId/members` | Add member | central_data_entry+ |
| PATCH | `/projects/:projectId/members/:id` | Update member | central_data_entry+ |
| DELETE | `/projects/:projectId/members/:id` | Remove member | central_data_entry+ |

### GET /projects/:projectId/members

```
Response: [{
  "id": "uuid",
  "person_name": "Dr. Ahmed Hassan",
  "organization_name": "Quality Lab",
  "role_name": "Supervisor",
  "notes": "Project lead"
}]
```

### POST /projects/:projectId/members

```
Request: {
  "person_name": "string (required)",
  "organization_name": "string?",
  "role_name": "string?",
  "notes": "string?"
}
Response: 201 Created
Errors: 422 if project is APPROVED/ARCHIVED (immutable)
```

### PATCH /projects/:projectId/members/:id

```
Request: { "person_name": "string?", "organization_name": "string?", "role_name": "string?", "notes": "string?" }
Response: 200 OK
```

### DELETE /projects/:projectId/members/:id

```
Response: 204 No Content
Errors: 422 if project is APPROVED/ARCHIVED
```

---

## 9. File Categories

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| GET | `/file-categories` | List categories | Yes |
| POST | `/file-categories` | Create category | system_admin |
| GET | `/file-categories/:id` | Get one category | Yes |
| PATCH | `/file-categories/:id` | Update category | system_admin |
| DELETE | `/file-categories/:id` | Deactivate category | system_admin |

### GET /file-categories

```
Query: ?is_active=true&include_types=true

Response: [{
  "id": "uuid",
  "code": "DOCUMENTS",
  "name": "Documents",
  "description": "Official documents and reports",
  "is_active": true,
  "files_count": 340,
  "types": [
    { "id": "uuid", "code": "PDF", "name": "PDF Document", "default_extension": "pdf" },
    { "id": "uuid", "code": "WORD", "name": "Word Document", "default_extension": "docx" }
  ]
}]
```

### POST /file-categories

```
Request: { "code": "string (unique)", "name": "string", "description": "string?" }
Response: 201 Created
```

### GET /file-categories/:id

```
Response: { category object with types and files_count }
```

### PATCH /file-categories/:id

```
Request: { "name": "string?", "description": "string?", "is_active": boolean? }
Response: 200 OK
```

### DELETE /file-categories/:id

```
Response: 204 No Content (soft deactivation)
Errors: 422 if has active files
```

---

## 10. File Types

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| GET | `/file-types` | List types | Yes |
| POST | `/file-types` | Create type | system_admin |
| GET | `/file-types/:id` | Get one type | Yes |
| PATCH | `/file-types/:id` | Update type | system_admin |
| DELETE | `/file-types/:id` | Deactivate type | system_admin |

### GET /file-types

```
Query: ?category_id=uuid&is_active=true

Response: [{
  "id": "uuid",
  "category": { "id": "uuid", "code": "DOCUMENTS", "name": "Documents" },
  "code": "PDF",
  "name": "PDF Document",
  "default_extension": "pdf",
  "description": "...",
  "is_active": true,
  "files_count": 200
}]
```

### POST /file-types

```
Request: {
  "category_id": "uuid (required)",
  "code": "string (unique)",
  "name": "string",
  "default_extension": "string?",
  "description": "string?"
}
Response: 201 Created
Errors: 422 if category_id not found
```

### GET /file-types/:id

```
Response: { type object with category and files_count }
```

### PATCH /file-types/:id

```
Request: { "name": "string?", "default_extension": "string?", "description": "string?", "is_active": boolean? }
Response: 200 OK
```

### DELETE /file-types/:id

```
Response: 204 No Content (soft deactivation)
```

---

## 11. Files

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| GET | `/projects/:projectId/files` | List project files | Yes |
| POST | `/projects/:projectId/files/upload` | Upload file(s) | central_data_entry+ |
| GET | `/files/:id` | Get file metadata | Yes |
| PATCH | `/files/:id` | Update file metadata | central_data_entry+ |
| DELETE | `/files/:id` | Soft delete file | central_data_entry+ |
| GET | `/files/:id/download` | Download file | Yes |
| POST | `/files/:id/restore` | Restore deleted file | system_admin |

### GET /projects/:projectId/files

```
Query: ?category_id=uuid&type_id=uuid&status=ACTIVE&sort=created_at&order=desc

Response: [{
  "id": "uuid",
  "original_name": "lab_report_2025.pdf",
  "stored_name": "a1b2c3d4-e5f6-7890.pdf",
  "category": { "id": "uuid", "code": "DOCUMENTS", "name": "Documents" },
  "type": { "id": "uuid", "code": "PDF", "name": "PDF Document" },
  "confidentiality_level": { "id": "uuid", "name": "Internal" },
  "extension": "pdf",
  "mime_type": "application/pdf",
  "size_bytes": 2457600,
  "checksum_sha256": "abc123...",
  "status": "ACTIVE",
  "source_type": "MANUAL",
  "uploaded_by": { "id": "uuid", "full_name": "Admin" },
  "created_at": "2025-01-20T10:00:00Z"
}]
```

### POST /projects/:projectId/files/upload

Multipart form data upload.

```
Request (multipart/form-data):
  file: <binary>
  category_id: "uuid"
  type_id: "uuid?"
  confidentiality_level_id: "uuid?"
  source_type: "MANUAL|BRANCH_PACKAGE|MIGRATION?"
  source_reference: "string?"

Response: 201 Created { file metadata object }

Logic:
  1. Receive file upload
  2. Generate stored_name (UUID + extension)
  3. Upload to MinIO: bucket = "projects/{project_id}", key = "{year}/{month}/{stored_name}"
  4. Calculate SHA-256 checksum
  5. Check for duplicate checksum
  6. Store metadata in files table
  7. Return file metadata

Multiple files: Use /upload-batch endpoint
```

### POST /projects/:projectId/files/upload-batch

```
Request (multipart/form-data):
  files: [<binary>, <binary>, ...]
  category_id: "uuid"
  type_id: "uuid?"

Response: 201 Created {
  "uploaded": 5,
  "failed": 1,
  "results": [
    { "original_name": "file1.pdf", "status": "SUCCESS", "file": { ... } },
    { "original_name": "file2.pdf", "status": "FAILED", "error": "Duplicate checksum" }
  ]
}
```

### GET /files/:id

```
Query: ?include=project,category,type,confidentiality_level,uploaded_by

Response: {
  id, original_name, stored_name, bucket_name, object_key,
  extension, mime_type, size_bytes, checksum_sha256,
  status, source_type, source_reference,
  category: { id, code, name },
  type: { id, code, name },
  confidentiality_level: { id, name, level_order },
  project: { id, name, project_number, year },
  uploaded_by: { id, full_name },
  deleted_at, deleted_by, delete_reason,
  created_at
}
```

### PATCH /files/:id

```
Request: {
  "category_id": "uuid?",
  "type_id": "uuid?",
  "confidentiality_level_id": "uuid?"
}
Response: 200 OK
Errors: 422 if file is DELETED
```

### DELETE /files/:id

```
Request: { "reason": "string?" } (optional)
Response: 204 No Content

Logic:
  1. Set status = DELETED
  2. Set deleted_at = now()
  3. Set deleted_by = current user
  4. Set delete_reason = provided reason
  5. Do NOT delete from MinIO (soft delete only)
```

### GET /files/:id/download

```
Response: Binary file stream
Headers: Content-Type: {mime_type}, Content-Disposition: inline; filename="{original_name}"
Errors: 403 if confidentiality_level exceeds user's max
```

### POST /files/:id/restore

```
Response: 200 OK { status: "ACTIVE", deleted_at: null }
Errors: 422 if file was not DELETED
```

---

## 12. Branch Packages (Import Workflow)

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| GET | `/packages` | List packages | Yes |
| POST | `/packages/receive` | Receive package from terminal | import_officer+ |
| GET | `/packages/:id` | Get one package | Yes |
| POST | `/packages/:id/verify` | Verify package integrity | import_officer+ |
| POST | `/packages/:id/import` | Import package contents | import_officer+ |
| GET | `/packages/:id/projects` | List projects in package | Yes |
| GET | `/packages/:id/operations` | List import operations | Yes |

### GET /packages

```
Query: ?status=RECEIVED|VERIFIED|IMPORTED|FAILED|DUPLICATE&source_organization_id=uuid&sort=received_at&order=desc

Response: [{
  "id": "uuid",
  "package_uuid": "uuid",
  "package_name": "branch_export_2025_001.pkg",
  "source_organization": { "id": "uuid", "name": "Factory A" },
  "source_terminal_uuid": "uuid",
  "source_username": "terminal_admin",
  "schema_version": "1.0",
  "projects_count": 5,
  "files_count": 23,
  "status": "VERIFIED",
  "received_by": { "id": "uuid", "full_name": "Import Officer" },
  "received_at": "2025-06-01T10:00:00Z"
}]
```

### POST /packages/receive

Upload and receive a package file from a branch terminal.

```
Request (multipart/form-data):
  package_file: <binary .pkg file>

Response: 201 Created {
  id: "uuid",
  package_uuid: "uuid-from-manifest",
  package_name: "branch_export_2025_001.pkg",
  package_hash: "sha256...",
  manifest_hash: "sha256...",
  schema_version: "1.0",
  status: "RECEIVED",
  ...
}

Logic:
  1. Receive encrypted package file
  2. Store package temporarily (or stream to MinIO)
  3. Extract manifest (unencrypted portion)
  4. Validate manifest schema_version
  5. Calculate package_hash
  6. Check for duplicate (package_uuid or package_hash)
  7. If duplicate -> status = DUPLICATE
  8. Create branch_packages record with status = RECEIVED
  9. Return package metadata
```

### GET /packages/:id

```
Query: ?include=projects,organization,received_by

Response: {
  id, package_uuid, package_name, package_hash, manifest_hash,
  schema_version, projects_count, files_count, status,
  source_organization: { ... },
  source_terminal_uuid, source_username,
  package_projects: [{
    id, local_project_uuid, import_status,
    project: { id, name, project_number, status }
  }],
  received_by: { ... },
  received_at
}
```

### POST /packages/:id/verify

```
Response: 200 OK {
  status: "VERIFIED",
  verification_details: {
    manifest_valid: true,
    checksum_match: true,
    schema_compatible: true,
    projects_extractable: 5,
    files_extractable: 23
  }
}

Logic:
  1. Verify package_hash matches stored file
  2. Re-extract and validate manifest
  3. Check all file checksums within manifest
  4. Validate schema_version compatibility
  5. Update status to VERIFIED or FAILED
  6. Return detailed verification results

Errors: 422 if status is not RECEIVED or DUPLICATE
```

### POST /packages/:id/import

```
Request: {
  "organization_mapping": {
    "terminal_org_uuid_1": "central_org_uuid_1",
    "terminal_org_uuid_2": "central_org_uuid_2"
  },
  "confidentiality_level_id": "uuid? (default level for imported projects)"
}

Response: 200 OK {
  import_id: "uuid",
  status: "STARTED",
  message: "Import started in background"
}

Background Process:
  1. Create import_operations record (status=STARTED)
  2. For each project in manifest:
     a. Map organization_id using organization_mapping
     b. Create project record (status=IMPORTED)
     c. Create project_members from manifest
     d. For each file in project:
        - Download from package/archive
        - Upload to MinIO
        - Create file record
     e. Create package_projects link
  3. Update import_operations (status=SUCCESS|PARTIAL|FAILED)
  4. Update branch_packages status to IMPORTED
  5. Create audit_logs entries for all operations
```

### GET /packages/:id/projects

```
Response: [{
  "id": "uuid",
  "local_project_uuid": "uuid-from-terminal",
  "import_status": "IMPORTED",
  "project": {
    "id": "uuid",
    "name": "Lab Renovation",
    "project_number": "PRJ-2025-010",
    "status": "IMPORTED",
    "version_no": 1
  }
}]
```

### GET /packages/:id/operations

```
Response: [{
  "id": "uuid",
  "imported_by": { "id": "uuid", "full_name": "Import Officer" },
  "status": "SUCCESS",
  "error_message": null,
  "started_at": "2025-06-01T11:00:00Z",
  "completed_at": "2025-06-01T11:05:30Z"
}]
```

---

## 13. Migration Sources & Batches

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| GET | `/migration-sources` | List sources | Yes |
| POST | `/migration-sources` | Create source | system_admin |
| GET | `/migration-sources/:id` | Get one source | Yes |
| PATCH | `/migration-sources/:id` | Update source | system_admin |
| DELETE | `/migration-sources/:id` | Delete source | system_admin |
| GET | `/migration-sources/:id/batches` | List batches | Yes |
| POST | `/migration-sources/:id/batches` | Create batch | system_admin |
| GET | `/migration-batches/:id` | Get one batch | Yes |
| PATCH | `/migration-batches/:id` | Update batch | system_admin |
| POST | `/migration-batches/:id/run` | Execute migration | system_admin |
| POST | `/migration-batches/:id/complete` | Mark batch done | system_admin |

### GET /migration-sources

```
Response: [{
  "id": "uuid",
  "name": "Old System - Factory A",
  "source_type": "PostgreSQL",
  "database_name": "old_archive_db",
  "office_name": "Factory A Office",
  "description": "...",
  "batches_count": 3,
  "created_at": "2025-01-01T00:00:00Z"
}]
```

### POST /migration-sources

```
Request: {
  "name": "string (required)",
  "source_type": "string? (PostgreSQL, File, Manual)",
  "database_name": "string?",
  "office_name": "string?",
  "description": "string?"
}
Response: 201 Created
```

### GET /migration-sources/:id

```
Response: { source object with batches summary }
```

### PATCH /migration-sources/:id

```
Request: { "name": "string?", "description": "string?", ... }
Response: 200 OK
```

### DELETE /migration-sources/:id

```
Response: 204 No Content
Errors: 422 if has running batches
```

### GET /migration-sources/:id/batches

```
Response: [{
  "id": "uuid",
  "batch_number": "BATCH-001",
  "status": "SUCCESS",
  "projects_count": 150,
  "files_count": 420,
  "started_at": "2025-03-01T08:00:00Z",
  "completed_at": "2025-03-01T12:00:00Z",
  "notes": "First migration batch"
}]
```

### POST /migration-sources/:id/batches

```
Request: {
  "batch_number": "string (required, unique per source)",
  "notes": "string?"
}
Response: 201 Created { status: "PLANNED" }
```

### GET /migration-batches/:id

```
Response: {
  id, batch_number, status, projects_count, files_count,
  started_at, completed_at, notes,
  source: { id, name, source_type, database_name }
}
```

### PATCH /migration-batches/:id

```
Request: { "notes": "string?" }
Response: 200 OK
Errors: 422 if status is RUNNING or SUCCESS
```

### POST /migration-batches/:id/run

```
Request: {
  "migration_config": {
    "connection_string": "string? (if PostgreSQL)",
    "file_path": "string? (if File)",
    "project_type_mapping": { "old_type": "new_type_id" },
    "default_confidentiality_level_id": "uuid?"
  }
}
Response: 200 OK { status: "RUNNING" }

Background Process:
  1. Validate source connection/access
  2. Update batch status to RUNNING
  3. Create import_operations-style tracking
  4. Extract data from old system
  5. Transform to new schema format
  6. Import projects and files
  7. Update batch counts and status
  8. Create audit_logs entries

Errors: 422 if status is not PLANNED or FAILED
```

### POST /migration-batches/:id/complete

```
Request: {
  "status": "SUCCESS|PARTIAL|FAILED",
  "notes": "string?"
}
Response: 200 OK
```

---

## 14. Audit Logs

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| GET | `/audit-logs` | Search audit logs | system_admin |
| GET | `/audit-logs/:id` | Get one log entry | system_admin |
| GET | `/audit-logs/entity/:type/:id` | Get entity history | system_admin |

### GET /audit-logs

```
Query:
  ?user_id=uuid
  &action=CREATE_PROJECT|UPDATE_PROJECT|DELETE_FILE|...
  &entity_type=project|file|user|...
  &entity_id=uuid
  &date_from=2025-01-01
  &date_to=2025-12-31
  &sort=created_at&order=desc
  &page=1&limit=50

Response: [{
  "id": "uuid",
  "user": { "id": "uuid", "full_name": "Admin User" },
  "action": "CREATE_PROJECT",
  "entity_type": "project",
  "entity_id": "uuid",
  "description": "Created project PRJ-2025-001",
  "old_values": null,
  "new_values": { "name": "Lab Renovation", "status": "DRAFT" },
  "ip_address": "192.168.1.100",
  "created_at": "2025-01-15T08:00:00Z"
}]
```

### GET /audit-logs/:id

```
Response: Full log entry with old_values and new_values expanded
```

### GET /audit-logs/entity/:type/:id

Get complete history of a specific entity.

```
Query: ?page=1&limit=50
URL: /audit-logs/entity/project/uuid-of-project

Response: [{
  action: "CREATE",
  user: { ... },
  new_values: { ... },
  created_at: "..."
}, {
  action: "UPDATE",
  user: { ... },
  old_values: { status: "DRAFT" },
  new_values: { status: "UNDER_REVIEW" },
  created_at: "..."
}, {
  action: "APPROVE",
  user: { ... },
  old_values: { approved_by: null },
  new_values: { approved_by: "uuid", approved_at: "..." },
  created_at: "..."
}]
```

---

## 15. Dashboard & Statistics

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| GET | `/dashboard/stats` | Overview statistics | Yes |
| GET | `/dashboard/activity` | Recent activity feed | Yes |

### GET /dashboard/stats

```
Response: {
  "organizations": { "total": 45, "active": 42 },
  "users": { "total": 28, "active": 25 },
  "projects": {
    "total": 1250,
    "by_status": { "DRAFT": 45, "IMPORTED": 120, ... },
    "by_year": { "2025": 180, "2024": 320 },
    "by_source": { "MANUAL": 600, "BRANCH_PACKAGE": 400, "MIGRATION": 250 }
  },
  "files": { "total": 8500, "total_size_bytes": 53687091200 },
  "packages": { "received": 120, "pending_import": 15 },
  "pending_reviews": 30
}
```

### GET /dashboard/activity

```
Query: ?limit=20

Response: [{
  "action": "IMPORT_PACKAGE",
  "user": { "full_name": "Import Officer" },
  "description": "Imported package with 5 projects from Factory A",
  "entity_type": "package",
  "entity_id": "uuid",
  "created_at": "2025-06-01T11:05:30Z"
}, ...]
```

---

## Summary: Endpoint Count by Resource

| Resource | Endpoints |
|----------|-----------|
| Auth | 5 |
| Organizations | 8 |
| Users | 9 |
| Roles | 5 |
| Confidentiality Levels | 5 |
| Project Types | 5 |
| Projects | 12 |
| Project Members | 4 |
| File Categories | 5 |
| File Types | 5 |
| Files | 7 |
| Packages | 7 |
| Migration Sources | 5 |
| Migration Batches | 6 |
| Audit Logs | 3 |
| Dashboard | 2 |
| **Total** | **93** |

## Recommended Implementation Order

1. **Auth** - Foundation for all endpoints
2. **Organizations** - Needed for users and projects
3. **Confidentiality Levels** - Used across entities
4. **Project Types** - Used by projects
5. **Roles** + **User Roles** - User management
6. **Users** - Core entity
7. **File Categories** + **File Types** - File management
8. **Projects** + **Project Members** - Core workflow
9. **Files** - File upload/download
10. **Packages** - Import workflow
11. **Migration** - Data migration
12. **Audit Logs** - Cross-cutting
13. **Dashboard** - Aggregation views
