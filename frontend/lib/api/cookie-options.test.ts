import { afterEach, describe, expect, it, vi } from "vitest";
import { authCookiesSecure } from "./cookie-options";

afterEach(() => vi.unstubAllEnvs());

describe("authentication cookie transport", () => {
  it("defaults to secure cookies in production", () => {
    vi.stubEnv("NODE_ENV", "production");
    vi.stubEnv("AUTH_COOKIE_SECURE", undefined);
    expect(authCookiesSecure()).toBe(true);
  });
  it("allows the explicit local Docker HTTP setting", () => {
    vi.stubEnv("NODE_ENV", "production");
    vi.stubEnv("AUTH_COOKIE_SECURE", "false");
    expect(authCookiesSecure()).toBe(false);
  });
  it("keeps invalid values secure in production", () => {
    vi.stubEnv("NODE_ENV", "production");
    vi.stubEnv("AUTH_COOKIE_SECURE", "invalid");
    expect(authCookiesSecure()).toBe(true);
  });
});
