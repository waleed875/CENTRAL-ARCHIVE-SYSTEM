import "server-only";
import { cookies } from "next/headers";

const baseUrl = process.env.CENTRAL_ARCHIVE_API_URL;
const tokenCookie = "ca_access_token";

export class ApiError extends Error {
  constructor(public readonly status: number, message: string) { super(message); }
}

export async function serverRequest(path: string, init: RequestInit = {}) {
  if (!baseUrl) throw new ApiError(500, "لم يتم إعداد عنوان خدمة الواجهة الخلفية.");
  const token = (await cookies()).get(tokenCookie)?.value;
  if (!token) throw new ApiError(401, "الجلسة غير صالحة.");
  const headers = new Headers(init.headers);
  headers.set("Authorization", `Bearer ${token}`);
  const response = await fetch(new URL(path, baseUrl), { ...init, headers, cache: "no-store" });
  if (!response.ok) throw new ApiError(response.status, "تعذر إكمال الطلب.");
  return response;
}

export { tokenCookie };
