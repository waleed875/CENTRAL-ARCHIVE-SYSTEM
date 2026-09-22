import { describe, expect, it } from "vitest";
import { loginSchema } from "./contracts";

describe("loginSchema", () => {
  it("accepts credentials supplied by the form", () => expect(loginSchema.safeParse({ username: "admin", password: "password" }).success).toBe(true));
  it("rejects empty credentials", () => expect(loginSchema.safeParse({ username: "", password: "" }).success).toBe(false));
});
