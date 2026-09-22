import "server-only";
import { cookies } from "next/headers";

export const backendBaseUrl = process.env.CENTRAL_ARCHIVE_API_URL;
export const accessTokenCookie = "ca_access_token";
export const refreshTokenCookie = "ca_refresh_token";

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    public readonly code?: string,
  ) {
    super(message);
  }
}

async function extractUpstreamError(response: Response) {
  const fallback = response.statusText || "تعذر إكمال الطلب.";

  try {
    const body = await response.clone().json() as {
      meta?: { message?: unknown };
      data?: { message?: unknown; code?: unknown };
      message?: unknown;
      error?: unknown;
    };

    const message =
      typeof body?.data?.message === "string"
        ? body.data.message
        : typeof body?.meta?.message === "string"
          ? body.meta.message
          : typeof body?.message === "string"
            ? body.message
            : typeof body?.error === "string"
              ? body.error
              : fallback;

    const code =
      typeof body?.data?.code === "string" ? body.data.code : undefined;

    return { message, code };
  } catch {
    const text = await response.clone().text().catch(() => "");
    return {
      message: text.trim() || fallback,
      code: undefined,
    };
  }
}

export async function serverRequest(path: string, init: RequestInit = {}) {
  if (!backendBaseUrl) {
    throw new ApiError(500, "لم يتم إعداد عنوان خدمة الواجهة الخلفية.");
  }

  const token = (await cookies()).get(accessTokenCookie)?.value;
  if (!token) {
    throw new ApiError(401, "الجلسة غير صالحة.");
  }

  const headers = new Headers(init.headers);
  headers.set("Authorization", `Bearer ${token}`);

  const response = await fetch(new URL(path, backendBaseUrl), {
    ...init,
    headers,
    cache: "no-store",
  });

  if (!response.ok) {
    const upstream = await extractUpstreamError(response);
    throw new ApiError(response.status, upstream.message, upstream.code);
  }

  return response;
}
