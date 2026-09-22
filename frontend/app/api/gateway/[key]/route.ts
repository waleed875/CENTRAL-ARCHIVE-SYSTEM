import { NextRequest, NextResponse } from "next/server";
import { endpointByKey } from "@/lib/api/catalog";
import { ApiError, serverRequest } from "@/lib/api/server";

function interpolate(path: string, params: Record<string, string>) {
  return path.replace(/:([A-Za-z]+)/g, (_, name: string) => {
    const value = params[name];
    if (!value) throw new ApiError(400, "معرّف مطلوب مفقود.");
    return encodeURIComponent(value);
  });
}

async function handler(request: NextRequest, context: { params: Promise<{ key: string }> }) {
  const endpoint = endpointByKey.get((await context.params).key);
  if (!endpoint || endpoint.method !== request.method) return NextResponse.json({ message: "العملية غير مسموحة." }, { status: 404 });
  try {
    const params = Object.fromEntries(request.nextUrl.searchParams.getAll("param").map((value) => value.split("=", 2)));
    const query = new URLSearchParams();
    request.nextUrl.searchParams.forEach((value, key) => { if (key !== "param" && value) query.append(key, value); });
    const url = `${interpolate(endpoint.path, params)}${query.size ? `?${query}` : ""}`;
    const isJson = endpoint.body === "json";
    const isFormData = endpoint.body === "form-data";
    const body = isJson
      ? JSON.stringify(await request.json().catch(() => ({})))
      : isFormData
        ? await request.formData()
        : undefined;
    const upstream = await serverRequest(url, { method: endpoint.method, headers: isJson ? { "Content-Type": "application/json" } : undefined, body });
    const contentType = upstream.headers.get("content-type") ?? "application/octet-stream";
    const contentDisposition = upstream.headers.get("content-disposition");
    const responseHeaders = new Headers({ "Content-Type": contentType });
    if (contentDisposition) responseHeaders.set("Content-Disposition", contentDisposition);
    return new NextResponse(upstream.body, { status: upstream.status, headers: responseHeaders });
  } catch (error) {
    const status = error instanceof ApiError ? error.status : 502;
    const message = error instanceof ApiError ? error.message : "تعذر الاتصال بالخدمة.";
    return NextResponse.json({ message }, { status });
  }
}

export const GET = handler;
export const POST = handler;
export const PATCH = handler;
export const DELETE = handler;
