type DataRecord = Record<string, unknown>;
function record(value: unknown): value is DataRecord { return typeof value === "object" && value !== null && !Array.isArray(value); }

export function isSystemAdminResponse(response: unknown): boolean | null {
  return hasAnyRoleResponse(response, ["system_admin"]);
}

export function hasAnyRoleResponse(response: unknown, allowedRoles: string[]): boolean | null {
  const data = record(response) && "data" in response ? response.data : response;
  const user = record(data) && record(data.user) ? data.user : data;
  if (!record(user)) return null;
  const roles = user.roles ?? user.authorities ?? user.role;
  if (roles === undefined || roles === null) return null;
  const items = Array.isArray(roles) ? roles : [roles];
  const allowed = new Set(allowedRoles.map((role) => role.toLowerCase().replace(/^role_/, "")));
  return items.some((item) => {
    const value = typeof item === "string" ? item : record(item) ? item.code ?? item.name ?? item.authority ?? item.roleName : null;
    return typeof value === "string" && allowed.has(value.toLowerCase().replace(/^role_/, ""));
  });
}

export function canDeleteProjectResponse(response: unknown): boolean | null {
  const data = record(response) && "data" in response ? response.data : response;
  const user = record(data) && record(data.user) ? data.user : data;
  if (!record(user)) return null;
  const roles = user.roles ?? user.authorities ?? user.role;
  if (roles === undefined || roles === null) return null;
  const items = Array.isArray(roles) ? roles : [roles];
  return items.some((item) => {
    const value = typeof item === "string" ? item : record(item) ? item.code ?? item.name ?? item.authority ?? item.roleName : null;
    return typeof value === "string" && ["system_admin", "central_data_entry"].includes(value.toLowerCase().replace(/^role_/, ""));
  });
}
