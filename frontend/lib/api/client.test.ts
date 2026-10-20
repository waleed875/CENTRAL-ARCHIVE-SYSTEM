import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiClientError, ApiRequestError, apiRequest } from "./client";

afterEach(() => vi.unstubAllGlobals());

describe("browser API gateway client", () => {
  it("refreshes the session and retries requests made by the imported screens", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(Response.json({ success: true }))
      .mockResolvedValueOnce(Response.json({ data: { id: "project-1" } }));
    vi.stubGlobal("fetch", fetchMock);
    await expect(apiRequest("projects.get", "GET", {}, undefined, { id: "project-1" }))
      .resolves.toEqual({ data: { id: "project-1" } });
    expect(fetchMock.mock.calls[1]).toEqual(["/api/auth/refresh", { method: "POST" }]);
    expect(fetchMock.mock.calls[2][0]).toBe(fetchMock.mock.calls[0][0]);
  });

  it("preserves backend codes and both frontend error names", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(
      Response.json({ message: "Project cannot be deleted", code: "PROJECT_NOT_DRAFT" }, { status: 409 }),
    ));
    const error = await apiRequest("projects.delete", "DELETE", {}, undefined, { id: "project-1" })
      .catch((failure: unknown) => failure);
    expect(error).toBeInstanceOf(ApiClientError);
    expect(error).toBeInstanceOf(ApiRequestError);
    expect(error).toMatchObject({ status: 409, code: "PROJECT_NOT_DRAFT" });
  });

  it("accepts successful deletes without a response body", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 204 })));
    await expect(apiRequest("projects.delete", "DELETE", {}, undefined, { id: "project-1" }))
      .resolves.toBeUndefined();
  });
  it("passes route identifiers and query fields to the allow-listed gateway", async () => {
    const fetchMock = vi.fn().mockResolvedValue(Response.json({ success: true }));
    vi.stubGlobal("fetch", fetchMock);
    await apiRequest("files.delete", "DELETE", { reason: "نسخة مكررة" }, undefined, { id: "file/1" });
    const [url, options] = fetchMock.mock.calls[0] as [string, RequestInit];
    const parsed = new URL(url, "http://localhost");
    expect(parsed.pathname).toBe("/api/gateway/files.delete");
    expect(parsed.searchParams.get("param")).toBe("id=file/1");
    expect(parsed.searchParams.get("reason")).toBe("نسخة مكررة");
    expect(options.method).toBe("DELETE");
  });

  it("sends multipart bodies without a JSON content type", async () => {
    const fetchMock = vi.fn().mockResolvedValue(Response.json({ success: true }));
    vi.stubGlobal("fetch", fetchMock);
    const body = new FormData();
    body.append("metadata", "{}");
    await apiRequest("files.upload", "POST", {}, body, { id: "project-1" });
    const [, options] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(options.body).toBe(body);
    expect(options.headers).toBeUndefined();
  });
});
