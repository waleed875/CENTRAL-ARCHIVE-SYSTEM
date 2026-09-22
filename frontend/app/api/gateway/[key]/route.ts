import { NextRequest, NextResponse } from "next/server";
import { endpointByKey } from "@/lib/api/catalog";
import { ApiError, serverRequest } from "@/lib/api/server";

function interpolate(path: string, params: Record<string, string>) {
  return path.replace(/:([A-Za-z]+)/g, (_, name: string) => {
    const value = params[name];
    if (!value) {
      throw new ApiError(400, "معرّف مطلوب مفقود.");
    }
    return encodeURIComponent(value);
  });
}

async function handler(
  request: NextRequest,
  context: { params: Promise<{ key: string }> },
) {
  const endpoint = endpointByKey.get((await context.params).key);
  if (!endpoint || endpoint.method !== request.method) {
    return NextResponse.json(
      { message: "العملية غير مسموحة." },
      { status: 404 },
    );
  }

  try {
    const pathParams: Record<string, string> = {};
    for (const value of request.nextUrl.searchParams.getAll("param")) {
      const separator = value.indexOf("=");
      if (separator <= 0) continue;
      const name = value.slice(0, separator);
      const paramValue = value.slice(separator + 1);
      if (name && paramValue) {
        pathParams[name] = paramValue;
      }
    }

    const query = new URLSearchParams();
    request.nextUrl.searchParams.forEach((value, key) => {
      if (key !== "param" && value) {
        query.append(key, value);
      }
    });

    const url = `${interpolate(endpoint.path, pathParams)}${query.size ? `?${query}` : ""}`;
    const isJson = endpoint.body === "json";
    const isFormData = endpoint.body === "form-data";

    const headers = new Headers();
    let body: BodyInit | undefined;
    const init: RequestInit & { duplex?: "half" } = {
      method: endpoint.method,
      headers,
    };

    if (isJson) {
      headers.set("Content-Type", "application/json");
      body = JSON.stringify(await request.json().catch(() => ({})));
    } else if (isFormData) {
      const contentType = request.headers.get("content-type");
      if (contentType) {
        headers.set("Content-Type", contentType);
      }
      body = request.body ?? undefined;
      if (body) {
        init.duplex = "half";
      }
    }

    init.body = body;

    const upstream = await serverRequest(url, init);
    const contentType =
      upstream.headers.get("content-type") ?? "application/octet-stream";
    const contentDisposition = upstream.headers.get("content-disposition");
    const responseHeaders = new Headers({ "Content-Type": contentType });

    if (contentDisposition) {
      responseHeaders.set("Content-Disposition", contentDisposition);
    }

    return new NextResponse(upstream.body, {
      status: upstream.status,
      headers: responseHeaders,
    });
  } catch (error) {
    const status = error instanceof ApiError ? error.status : 502;
    const message =
      error instanceof ApiError
        ? error.message
        : "تعذر الاتصال بالخدمة.";

    return NextResponse.json(
      {
        message,
        code: error instanceof ApiError ? error.code : undefined,
      },
      { status },
    );
  }
}

export const GET = handler;
export const POST = handler;
export const PATCH = handler;
export const DELETE = handler;
