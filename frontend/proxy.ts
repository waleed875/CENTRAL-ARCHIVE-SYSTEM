import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

const tokenCookie = "ca_access_token";

export function proxy(request: NextRequest) {
  const isAuthenticated = Boolean(request.cookies.get(tokenCookie)?.value);
  const isLoginPage = request.nextUrl.pathname === "/login";

  if (!isAuthenticated && !isLoginPage) {
    const loginUrl = new URL("/login", request.url);
    loginUrl.searchParams.set("next", request.nextUrl.pathname);
    return NextResponse.redirect(loginUrl);
  }

  return NextResponse.next();
}

export const config = {
  matcher: [
    "/login",
    "/dashboard/:path*",
    "/projects/:path*",
    "/organizations/:path*",
    "/users/:path*",
    "/roles/:path*",
    "/packages/:path*",
    "/exports/:path*",
    "/migrations/:path*",
    "/audit-logs/:path*",
    "/settings/:path*",
    "/operations/:path*",
    "/records/:path*",
    "/queries/:path*",
  ],
};
