"use client";

import type { HttpMethod } from "./catalog";

type Scalar = string | number | undefined;

export type ApiRequestOptions = {
  query?: Record<string, Scalar>;
  pathParams?: Record<string, Scalar>;
  body?: unknown;
};

function buildGatewayUrl(
  key: string,
  query: Record<string, Scalar>,
  pathParams: Record<string, Scalar>,
) {
  const params = new URLSearchParams();

  for (const [name, value] of Object.entries(query)) {
    if (value !== undefined && value !== "") {
      params.append(name, String(value));
    }
  }

  for (const [name, value] of Object.entries(pathParams)) {
    if (value !== undefined && value !== "") {
      params.append("param", `${name}=${String(value)}`);
    }
  }

  return `/api/gateway/${key}${params.size ? `?${params}` : ""}`;
}

async function sendRequest(
  key: string,
  method: HttpMethod,
  options: ApiRequestOptions,
): Promise<Response> {
  const query = options.query ?? {};
  const pathParams = options.pathParams ?? {};
  const body = options.body;
  const formData = typeof FormData !== "undefined" && body instanceof FormData;

  return fetch(buildGatewayUrl(key, query, pathParams), {
    method,
    headers:
      body === undefined || formData
        ? undefined
        : { "Content-Type": "application/json" },
    body:
      body === undefined
        ? undefined
        : formData
          ? body
          : JSON.stringify(body),
  });
}

export async function apiRequest<T>(
  key: string,
  method: HttpMethod,
  query: Record<string, Scalar> = {},
  body?: unknown,
  pathParams: Record<string, Scalar> = {},
): Promise<T> {
  const options = { query, body, pathParams };
  let response = await sendRequest(key, method, options);

  if (response.status === 401) {
    const refresh = await fetch("/api/auth/refresh", { method: "POST" });
    if (refresh.ok) {
      response = await sendRequest(key, method, options);
    }
  }

  if (!response.ok) {
    throw new Error("API_REQUEST_FAILED");
  }

  return response.json() as Promise<T>;
}
