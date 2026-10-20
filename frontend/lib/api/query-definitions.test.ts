import { describe, expect, it } from "vitest";
import { endpoints } from "./catalog";
import { queryByKey, queries } from "./query-definitions";

describe("Postman query pages", () => {
  it("covers every documented GET endpoint", () => {
    expect(queries.map((query) => query.key)).toEqual(endpoints.filter((endpoint) => endpoint.method === "GET" && !["migrationSettings.get", "projects.incompleteReport", "projects.reviewQueue", "packages.references"].includes(endpoint.key)).map((endpoint) => endpoint.key));
  });

  it("retains search inputs and download routes", () => {
    expect(queryByKey.get("projects.search")?.queryParams).toContain("searchTerm");
    expect(queryByKey.get("audit.entityHistory")?.pathParams).toEqual(["entityType", "id"]);
    expect(queryByKey.get("files.download")?.download).toBe(true);
    expect(queryByKey.get("exportPackages.manifest")?.download).toBe(true);
  });
  it("uses enums and reference lists for constrained filters", () => {
    expect(queryByKey.get("projects.search")?.optionSources.status?.enumKey).toBe("projectStatus");
    expect(queryByKey.get("projects.search")?.optionSources.projectTypeId?.endpoint).toBe("projectTypes.all");
    expect(queryByKey.get("users.list")?.optionSources.organizationId?.endpoint).toBe("organizations.all");
    expect(queryByKey.get("files.list")?.optionSources.typeId?.dependsOn).toMatchObject({ field: "categoryId", param: "id" });
    expect(queryByKey.get("audit.list")?.optionSources.action?.enumKey).toBe("auditAction");
  });
  it("shows Arabic titles for every query", () => {
    expect(queries.filter((query) => !/[\u0600-\u06FF]/.test(query.title)).map((query) => query.key)).toEqual([]);
  });
});
