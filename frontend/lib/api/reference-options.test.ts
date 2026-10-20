import { describe, expect, it } from "vitest";
import { readReferenceOptions, referenceTotalPages } from "./reference-options";

describe("reference option parsing", () => {
  it("reads bare and paginated API lists", () => {
    const bare = readReferenceOptions({ data: [{ id: "org-1", name: "المؤسسة" }] }, { endpoint: "organizations.all" });
    const paginated = readReferenceOptions({ data: { content: [{ id: "user-1", fullName: "مستخدم" }] } }, { endpoint: "users.list" });

    expect(bare).toEqual([{ value: "org-1", label: "المؤسسة" }]);
    expect(paginated).toEqual([{ value: "user-1", label: "مستخدم" }]);
  });

  it("formats organization hierarchy and uses explicit value fields", () => {
    const organizations = readReferenceOptions([
      { id: "parent", name: "الشركة" },
      { id: "child", name: "المصنع", parentId: "parent" },
    ], { endpoint: "organizations.all", labelStyle: "organization" });
    const confidentiality = readReferenceOptions(
      { data: [{ id: "level-id", code: "CONF-01", name: "سري" }] },
      { endpoint: "confidentialityLevels.all", valueField: "code", labelStyle: "code" },
    );

    expect(organizations[1]).toEqual({ value: "child", label: "الشركة ← المصنع" });
    expect(confidentiality).toEqual([{ value: "CONF-01", label: "سري — CONF-01" }]);
  });

  it("fails explicitly for missing enum sets and malformed lists", () => {
    expect(() => readReferenceOptions({ data: {} }, { enumKey: "projectStatus" })).toThrow("projectStatus");
    expect(() => readReferenceOptions({ data: { content: "invalid" } }, { endpoint: "users.list" })).toThrow("استجابة الخادم");
  });

  it("reads the page count from the API envelope", () => {
    expect(referenceTotalPages({ data: { content: [], totalPages: 3 } })).toBe(3);
    expect(referenceTotalPages({ data: [], meta: { totalPages: 2 } })).toBe(2);
    expect(referenceTotalPages({ data: [] })).toBeUndefined();
  });
});
