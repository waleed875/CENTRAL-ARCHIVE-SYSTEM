import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { authCookiesSecure } from "@/lib/api/cookie-options";
import {
  accessTokenCookie,
  backendBaseUrl,
  refreshTokenCookie,
} from "@/lib/api/server";
import { apiEnvelopeSchema } from "@/lib/api/contracts";

type RefreshData = {
  token?: unknown;
  refreshToken?: unknown;
  expiresIn?: unknown;
  refreshExpiresIn?: unknown;
};

function positiveSeconds(value: unknown, fallback: number) {
  return typeof value === "number" && Number.isFinite(value) && value > 0
    ? Math.floor(value)
    : fallback;
}

function clearAuthCookies(response: NextResponse) {
  const options = {
    httpOnly: true,
    sameSite: "lax" as const,
    secure: authCookiesSecure(),
    path: "/",
    maxAge: 0,
  };
  response.cookies.set(accessTokenCookie, "", options);
  response.cookies.set(refreshTokenCookie, "", options);
}

export async function POST() {
  if (!backendBaseUrl) {
    return NextResponse.json(
      { message: "إعداد الخدمة غير مكتمل." },
      { status: 500 },
    );
  }

  const refreshToken = (await cookies()).get(refreshTokenCookie)?.value;
  if (!refreshToken) {
    const response = NextResponse.json(
      { message: "انتهت الجلسة." },
      { status: 401 },
    );
    clearAuthCookies(response);
    return response;
  }

  try {
    const upstream = await fetch(
      new URL("/api/v1/auth/refresh", backendBaseUrl),
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ token: refreshToken }),
        cache: "no-store",
      },
    );

    const body = await upstream.json().catch(() => null);
    if (!upstream.ok) {
      const response = NextResponse.json(
        { message: "تعذر تجديد الجلسة." },
        { status: upstream.status },
      );
      clearAuthCookies(response);
      return response;
    }

    const result = apiEnvelopeSchema.parse(body);
    const data = (result.data ?? {}) as RefreshData;
    if (typeof data.token !== "string" || typeof data.refreshToken !== "string") {
      const response = NextResponse.json(
        { message: "استجابة تجديد الجلسة غير مكتملة." },
        { status: 502 },
      );
      clearAuthCookies(response);
      return response;
    }

    const response = NextResponse.json({ success: true });
    const options = {
      httpOnly: true,
      sameSite: "lax" as const,
      secure: authCookiesSecure(),
      path: "/",
    };

    response.cookies.set(accessTokenCookie, data.token, {
      ...options,
      maxAge: positiveSeconds(data.expiresIn, 60 * 60),
    });
    response.cookies.set(refreshTokenCookie, data.refreshToken, {
      ...options,
      maxAge: positiveSeconds(
        data.refreshExpiresIn,
        7 * 24 * 60 * 60,
      ),
    });

    return response;
  } catch {
    return NextResponse.json(
      { message: "تعذر الاتصال بالخدمة." },
      { status: 502 },
    );
  }
}
