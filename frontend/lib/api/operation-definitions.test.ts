import { describe, expect, it } from "vitest";
import { endpoints } from "./catalog";
import { operationByKey, operations } from "./operation-definitions";

describe("Postman operation pages", () => {
  it("provides a page for every user-facing mutation in the allow-list", () => {
    const expected = endpoints
      .filter((endpoint) => endpoint.method !== "GET" && !["auth.refresh", "auth.logout", "migrationSettings.update", "projects.delete", "packages.verify", "packages.import"].includes(endpoint.key))
      .map((endpoint) => endpoint.key);
    expect(operations.map((operation) => operation.key)).toEqual(expected);
  });

  it("includes documented project creation and upload fields", () => {
    const project = operationByKey.get("projects.create");
    expect(project?.fields.map((field) => field.name)).toContain("members");
    expect(project?.fields.map((field) => field.name)).toContain("organizationId");
    expect(project?.fields.find((field) => field.name === "projectTypeId")?.lookup).toBe("projectTypes.all");
    expect(operationByKey.get("files.upload")?.body).toBe("form-data");
    expect(operationByKey.get("files.upload")?.fields.map((field) => field.name)).toEqual(["file", "categoryId", "typeId", "confidentialityLevelId"]);
    expect(operationByKey.get("files.upload")?.fields.find((field) => field.name === "typeId")?.dependsOn).toMatchObject({ field: "categoryId", param: "id", pathParam: true });
    expect(operationByKey.get("packages.receive")?.fields[0]).toMatchObject({ name: "package_file", required: true, kind: "file" });
    expect(operationByKey.get("packages.receive")?.fields[1]).toMatchObject({ name: "passphrase", required: false, kind: "password" });
    expect(operationByKey.has("packages.verify")).toBe(false);
    expect(operationByKey.has("packages.import")).toBe(false);
  });

  it("keeps path and query parameters on their documented routes", () => {
    expect(operationByKey.get("users.addRole")?.pathParams).toEqual(["id"]);
    expect(operationByKey.get("users.addRole")?.queryFields.map((field) => field.name)).toEqual(["roleId"]);
    expect(operationByKey.get("projectMembers.update")?.pathParams).toEqual(["id", "memberId"]);
    expect(operationByKey.get("migrationBatches.complete")?.body).toBe("json");
    expect(operationByKey.get("migrationBatches.run")?.fields).toEqual([]);
    expect(operationByKey.get("users.create")?.fields.find((field) => field.name === "roleIds")).toMatchObject({ lookup: "roles.all", multiple: true });
    expect(operationByKey.get("exportPackages.create")?.fields.find((field) => field.name === "projectIds")?.pagination).toMatchObject({ pageParam: "page", sizeParam: "size" });
    expect(operationByKey.get("migrationSources.create")?.fields.find((field) => field.name === "sourceType")?.enumKey).toBe("migrationSourceType");
  });
  it("shows Arabic titles for every action", () => {
    expect(operations.filter((operation) => !/[\u0600-\u06FF]/.test(operation.title)).map((operation) => operation.key)).toEqual([]);
  });
});
