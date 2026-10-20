import { describe, expect, it } from "vitest";
import { appendJsonPart, memberPayload } from "./payloads";

describe("backend request payloads", () => {
  it("serializes project members with the names accepted by the backend", () => {
    expect(memberPayload({ personName: " Ali ", organizationName: " Office ", roleName: " Manager ", notes: "" })).toEqual({
      person_name: "Ali", organization_name: "Office", role_name: "Manager", notes: null,
    });
  });

  it("keeps the metadata request part as application/json through multipart parsing", async () => {
    const form = new FormData();
    appendJsonPart(form, "metadata", { categoryId: "one", typeId: "two" });
    const parsed = await new Request("http://localhost/upload", { method: "POST", body: form }).formData();
    const metadata = parsed.get("metadata");
    expect(metadata).toBeInstanceOf(File);
    expect((metadata as File).type).toBe("application/json");
    expect(JSON.parse(await (metadata as File).text())).toEqual({ categoryId: "one", typeId: "two" });
  });
});
