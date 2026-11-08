import type { NextRequest } from "next/server";

export function isSameOrigin(request: NextRequest): boolean {
  const origin = request.headers.get("origin");
  if (origin === null) return true;

  // Next.js can use its bind address (0.0.0.0) in nextUrl. Host preserves
  // the address and port used by the browser to reach this server.
  const host = request.headers.get("host") ?? request.nextUrl.host;
  try {
    return origin === new URL(`${request.nextUrl.protocol}//${host}`).origin;
  } catch {
    return false;
  }
}
