import { NextResponse } from "next/server";
import { loginSchema, apiEnvelopeSchema } from "@/lib/api/contracts";
import {
  accessTokenCookie,
  backendBaseUrl,
  refreshTokenCookie,
} from "@/lib/api/server";

type LoginData = {
  token?: unknown;
  refreshToken?: unknown;
  expiresIn?: unknown;
  refreshExpiresIn?: unknown;
  user?: unknown;
};

function positiveSeconds(value: unknown, fallback: number) {
  return typeof value === "number" && Number.isFinite(value) && value > 0
    ? Math.floor(value)
    : fallback;
}

export async function POST(request: Request) {
  if (!backendBaseUrl) {
    return NextResponse.json(
      { message: "إعداد الخدمة غير مكتمل." },
      { status: 500 },
    );
  }

  const payload = loginSchema.safeParse(
    await request.json().catch(() => null),
  );
  if (!payload.success) {
    return NextResponse.json(
      { message: "بيانات الدخول غير صالحة." },
      { status: 400 },
    );
  }

  try {
    const response = await fetch(
      new URL("/api/v1/auth/login", backendBaseUrl),
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload.data),
        cache: "no-store",
      },
    );

    const body = await response.json().catch(() => null);
    if (!response.ok) {
      return NextResponse.json(
        { message: "تعذر تسجيل الدخول." },
        { status: response.status },
      );
    }

    const result = apiEnvelopeSchema.parse(body);
    const data = (result.data ?? {}) as LoginData;
    if (typeof data.token !== "string" || typeof data.refreshToken !== "string") {
      return NextResponse.json(
        { message: "استجابة تسجيل الدخول غير مكتملة." },
        { status: 502 },
      );
    }

    const accessMaxAge = positiveSeconds(data.expiresIn, 60 * 60);
    const refreshMaxAge = positiveSeconds(
      data.refreshExpiresIn,
      7 * 24 * 60 * 60,
    );

    const out = NextResponse.json({
      data: {
        user: data.user,
        expiresIn: accessMaxAge,
      },
    });

    const cookieOptions = {
      httpOnly: true,
      sameSite: "lax" as const,
      secure: process.env.NODE_ENV === "production",
      path: "/",
    };

    out.cookies.set(accessTokenCookie, data.token, {
      ...cookieOptions,
      maxAge: accessMaxAge,
    });
    out.cookies.set(refreshTokenCookie, data.refreshToken, {
      ...cookieOptions,
      maxAge: refreshMaxAge,
    });

    return out;
  } catch {
    return NextResponse.json(
      { message: "تعذر الاتصال بالخدمة." },
      { status: 502 },
    );
  }
}
