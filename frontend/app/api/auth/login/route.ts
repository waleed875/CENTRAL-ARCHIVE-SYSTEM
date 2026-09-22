import { NextResponse } from "next/server";
import { loginSchema, apiEnvelopeSchema } from "@/lib/api/contracts";
import { tokenCookie } from "@/lib/api/server";

export async function POST(request: Request) {
  const baseUrl = process.env.CENTRAL_ARCHIVE_API_URL;
  if (!baseUrl) return NextResponse.json({ message: "إعداد الخدمة غير مكتمل." }, { status: 500 });
  const payload = loginSchema.safeParse(await request.json().catch(() => null));
  if (!payload.success) return NextResponse.json({ message: "بيانات الدخول غير صالحة." }, { status: 400 });
  try {
    const response = await fetch(new URL("/api/v1/auth/login", baseUrl), { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload.data), cache: "no-store" });
    const body = await response.json().catch(() => null);
    if (!response.ok) return NextResponse.json({ message: "تعذر تسجيل الدخول." }, { status: response.status });
    const result = apiEnvelopeSchema.parse(body);
    const token = (result.data as { token?: unknown } | undefined)?.token;
    if (typeof token !== "string") return NextResponse.json({ message: "استجابة تسجيل الدخول غير مكتملة." }, { status: 502 });
    const loginData = result.data as { user?: unknown; expiresIn?: unknown };
    const out = NextResponse.json({ data: { user: loginData.user, expiresIn: loginData.expiresIn } });
    out.cookies.set(tokenCookie, token, { httpOnly: true, sameSite: "lax", secure: process.env.NODE_ENV === "production", path: "/", maxAge: 60 * 60 });
    return out;
  } catch { return NextResponse.json({ message: "تعذر الاتصال بالخدمة." }, { status: 502 }); }
}
