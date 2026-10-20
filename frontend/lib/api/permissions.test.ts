import { describe, expect, it } from "vitest";
import { canDeleteProjectResponse, hasAnyRoleResponse, isSystemAdminResponse } from "./permissions";

describe("current user permissions", () => {
  it("recognizes admin roles in API envelopes", () => {
    expect(isSystemAdminResponse({ data: { roles: [{ name: "system_admin" }] } })).toBe(true);
    expect(isSystemAdminResponse({ data: { user: { roles: ["read_only"] } } })).toBe(false);
  });
  it("keeps unknown role shapes undecided", () => {
    expect(isSystemAdminResponse({ data: { username: "admin" } })).toBeNull();
  });
  it("recognizes package receiver and reviewer roles", () => {
    expect(hasAnyRoleResponse({ data: { roles: ["import_officer"] } }, ["system_admin", "import_officer"])).toBe(true);
    expect(hasAnyRoleResponse({ data: { user: { authorities: [{ authority: "ROLE_AUDITOR" }] } } }, ["auditor", "system_admin"])).toBe(true);
    expect(hasAnyRoleResponse({ data: { roles: ["read_only"] } }, ["auditor", "system_admin"])).toBe(false);
  });
});

describe("project delete permissions", () => {
  it("allows only the two backend roles", () => {
    expect(canDeleteProjectResponse({ data: { roles: ["system_admin"] } })).toBe(true);
    expect(canDeleteProjectResponse({ data: { roles: [{ code: "central_data_entry" }] } })).toBe(true);
    expect(canDeleteProjectResponse({ data: { roles: ["auditor"] } })).toBe(false);
    expect(canDeleteProjectResponse({ data: { roles: ["ADMIN"] } })).toBe(false);
  });
});
