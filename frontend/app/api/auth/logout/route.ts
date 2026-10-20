import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { authCookiesSecure } from "@/lib/api/cookie-options";
import {
  accessTokenCookie,
  backendBaseUrl,
  refreshTokenCookie,
} from "@/lib/api/server";

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
  const token = (await cookies()).get(accessTokenCookie)?.value;

  if (backendBaseUrl && token) {
    try {
      await fetch(new URL("/api/v1/auth/logout", backendBaseUrl), {
        method: "POST",
        headers: { Authorization: `Bearer ${token}` },
        cache: "no-store",
      });
    } catch {
      // Local cookies are still cleared if the backend is temporarily unreachable.
    }
  }

  const response = NextResponse.json({ success: true });
  clearAuthCookies(response);
  return response;
}
