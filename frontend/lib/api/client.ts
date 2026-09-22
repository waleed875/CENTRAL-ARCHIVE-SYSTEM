"use client";

import type { HttpMethod } from "./catalog";

export async function apiRequest<T>(key: string, method: HttpMethod, query: Record<string, string | number | undefined> = {}, body?: unknown): Promise<T> {
  const params = new URLSearchParams();
  for (const [name, value] of Object.entries(query)) if (value !== undefined && value !== "") params.set(name, String(value));
  const response = await fetch(`/api/gateway/${key}${params.size ? `?${params}` : ""}`, { method, headers: body === undefined ? undefined : { "Content-Type": "application/json" }, body: body === undefined ? undefined : JSON.stringify(body) });
  if (!response.ok) throw new Error("API_REQUEST_FAILED");
  return response.json() as Promise<T>;
}
