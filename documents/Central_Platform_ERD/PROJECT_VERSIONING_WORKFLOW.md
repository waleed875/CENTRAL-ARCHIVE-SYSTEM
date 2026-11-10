# Project lifecycle and versioning rules

This document is the implementation contract for project review and version history in the Central Archive System.

## 1. Logical project versus version record

A logical project is identified by `project_uid`. Each version is a separate row with its own `id`, `version_no`, `status`, files, review decision, and timestamps.

- `project_uid` remains constant across the version chain.
- `version_no` increases monotonically for each newly created version.
- `previous_project_id` points to the immediately preceding version.
- Exactly one row in a logical project chain should have `is_latest_version = true`.
- A review decision belongs to the version row reviewed; it must not overwrite the decision history of another version.

## 2. Three visible workflow stages

The project progress indicator displays three stages only:

1. **مسودة** — draft / imported data being prepared.
2. **قيد المراجعة** — submitted to an auditor.
3. **معتمد** — approved by an auditor.

Correction and rejection are outcomes/actions around the review stage, not additional progress stages. `ARCHIVED` remains a persisted/administrative status for existing records and archive handling, but it is not shown as a fourth step in the three-stage progress indicator. This preserves existing records and APIs while simplifying the user-facing workflow.

## 3. Status transition table

| Current status | Action | Next status | Actor / rule |
|---|---|---|---|
| `DRAFT` | Submit for review | `UNDER_REVIEW` | system_admin or central_data_entry; completeness must be COMPLETE |
| `IMPORTED` | Submit for review | `UNDER_REVIEW` | system_admin or central_data_entry; completeness must be COMPLETE |
| `NEEDS_CORRECTION` | Submit for review | `UNDER_REVIEW` | system_admin or central_data_entry; correction note is retained |
| `UNDER_REVIEW` | Request correction | `NEEDS_CORRECTION` | system_admin or auditor; review note required |
| `UNDER_REVIEW` | Approve | `APPROVED` | system_admin or auditor |
| `UNDER_REVIEW` | Reject | `REJECTED` | system_admin or auditor; rejection reason required |
| `REJECTED` | Reopen for correction | `NEEDS_CORRECTION` | system_admin or central_data_entry; latest version only |
| `APPROVED`, `ARCHIVED`, or `REJECTED` | Create next version | new row in `DRAFT` | system_admin or central_data_entry; source must be latest version |
| `APPROVED` | Archive | `ARCHIVED` | system_admin or archive_officer; retained for legacy/administrative handling |

Unsupported transitions must be rejected by the backend with a business-rule response. Hiding a button in the frontend is not an authorization control.

## 4. Rejection recovery

A rejected version is not silently reset and its rejection reason is not cleared.

Two explicit recovery paths are supported for the latest rejected version:

- **Reopen for correction:** `PATCH /api/v1/projects/{id}/reopen` changes the same version from `REJECTED` to `NEEDS_CORRECTION`. The original `rejection_reason` remains stored; subsequent correction instructions use `review_notes`.
- **Create a new version:** `POST /api/v1/projects/{id}/new-version` creates the next version in `DRAFT`, links it to the rejected version, and leaves the rejected version unchanged in history.

An obsolete version cannot be reopened or used as the source of another version. Users must act on the latest row.

## 5. New version copy policy

Creating a new version:

- Copies project metadata (organization, type, confidentiality, project number/code, name, year, description, notes, table of contents, keywords, source provenance).
- Copies project members to the new version.
- Does **not** copy files; files belong to a specific version and must be uploaded to the new version when appropriate.
- Does not copy the previous version's review status, correction note, or rejection decision into the new version. The previous row retains its history.
- Sets the new row to `DRAFT`, `is_latest_version = true`, and `previous_project_id = previous.id`; the source row becomes `is_latest_version = false`.

The logical project name is inherited from the previous version. Renaming is a separate, explicitly authorized metadata operation, not part of version creation.

## 6. Deleting an unsubmitted version draft

A draft successor may be deleted only when all of these conditions hold:

- Its status is `DRAFT` and it is the current latest version.
- No later version points to it.
- It has no attached files and no imported-package link.
- Its previous version exists in the same `project_uid` chain and is not currently marked latest.

On deletion, member rows copied into the draft are removed with that draft and the immediately previous version is restored to `is_latest_version = true`. The previous version's status and review history are not changed. A standalone draft retains the stricter existing rule that members must be removed before deleting it.

The system must never delete files or imported package records as a side effect of deleting a project draft.

## 7. Version history presentation

The version history should show, for each version:

- Version number and current/latest marker.
- Status.
- Created date and creator.
- Submission date and submitter where available.
- Approval date and approver where available.
- Correction note and rejection reason where available.

The detail page should keep the current version's files and review decision separate from those of previous versions. A decision on version N must not be rendered as the decision on version N+1.

## 8. Required regression coverage

Backend tests should cover:

- Valid and invalid status transitions, including incomplete projects refusing submission.
- Reopen of a rejected latest version and refusal to reopen an obsolete version.
- Creation of a new version from a rejected latest version.
- Prevention of two latest versions in one logical chain.
- Deleting an unsubmitted successor and restoring its parent as latest.
- Refusal to delete a draft with files, a package link, or descendant versions.
- Preservation of rejection reasons and correction notes across resubmission.
- Version-specific file and confidentiality access.
