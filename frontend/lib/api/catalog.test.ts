import { describe, expect, it } from "vitest";
import { endpointByKey, endpoints } from "./catalog";

describe("Postman endpoint allow-list", () => {
  it("contains only unique keys", () =>
    expect(new Set(endpoints.map((endpoint) => endpoint.key)).size).toBe(
      endpoints.length,
    ));

  it("exposes known project and dashboard operations", () => {
    expect(endpointByKey.get("projects.list")?.path).toBe("/api/v1/projects");
    expect(endpointByKey.get("dashboard.stats")?.method).toBe("GET");
  });

  it("tracks the current Postman export and rejection operations", () => {
    expect(endpointByKey.get("projects.reject")?.method).toBe("PATCH");
    expect(endpointByKey.get("exportPackages.list")?.path).toBe(
      "/api/v1/export/packages",
    );
    expect(endpointByKey.has("packages.receiveJson")).toBe(false);
  });

  it("matches request body modes used by the current backend contracts", () => {
    expect(endpointByKey.get("packages.receive")?.body).toBe("form-data");
    expect(endpointByKey.get("packages.import")?.body).toBe("json");
    expect(endpointByKey.get("migrationBatches.complete")?.body).toBe("json");
  });
});
