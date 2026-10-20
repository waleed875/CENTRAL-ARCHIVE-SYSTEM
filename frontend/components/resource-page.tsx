"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { ApiRequestError, apiRequest } from "@/lib/api/client";
import { messages } from "@/lib/i18n/messages";
import { DataView } from "@/components/data-view";
import { AdminLink } from "@/components/admin-link";

type Props = {
  title: string;
  endpoint: string;
  query?: Record<string, string | number>;
  createKey?: string;
  detailGroup?: string;
  extraLink?: { href: string; label: string };
};
export function ResourcePage({ title, endpoint, query = {}, createKey, detailGroup, extraLink }: Props) {
  const request = useQuery({
    queryKey: [endpoint, query],
    queryFn: () => apiRequest<unknown>(endpoint, "GET", query),
  });
  const t = messages.ar;
  return (
    <section className="space-y-5">
      <header className="flex flex-col justify-between gap-4 rounded-xl bg-white p-6 shadow-sm sm:flex-row sm:items-center">
        <div>
          <div className="flex items-center gap-3">
            <span className="h-7 w-2.5 rounded-full bg-[#000c0b]" />
            <h1 className="text-2xl font-bold text-[#1b1c15]">{title}</h1>
          </div>
        </div>
        <div className="flex items-center gap-2">
          {extraLink && <AdminLink href={extraLink.href} className="rounded-md border border-[#c1c8c6] bg-[#f6f4e9] px-4 py-2 text-sm font-medium text-[#002623]">{extraLink.label}</AdminLink>}
          {createKey && (createKey === "migrationSources.create" ? <AdminLink href={`/operations/${createKey}`} className="inline-flex items-center gap-2 rounded-md bg-[#002623] px-4 py-2 text-sm font-medium text-white transition hover:bg-[#33675d]"><Plus size={16} aria-hidden="true" />إضافة جديد</AdminLink> : <Link href={`/operations/${createKey}`} className="inline-flex items-center gap-2 rounded-md bg-[#002623] px-4 py-2 text-sm font-medium text-white transition hover:bg-[#33675d]"><Plus size={16} aria-hidden="true" />إضافة جديد</Link>)}
          <button onClick={() => request.refetch()} className="rounded-md border border-[#c1c8c6] bg-[#f6f4e9] px-4 py-2 text-sm font-medium text-[#002623] transition hover:bg-[#eae8dd]">{t.retry}</button>
        </div>
      </header>
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl bg-[#eae8dd] p-4">
        <span className="text-sm font-semibold text-[#1b1c15]">
          النتائج
        </span>
        <span className="rounded bg-[#f6f4e9] px-3 py-1 text-xs text-[#414847]">
          {request.isFetching ? "جارٍ التحديث" : "النتائج الحالية"}
        </span>
      </div>
      {request.isLoading && (
        <div className="rounded-xl bg-white p-10 text-center text-sm text-[#717977] shadow-sm">
          {t.loading}
        </div>
      )}
      {request.isError && (
        <div
          role="alert"
          className="rounded-xl border border-[#ba1a1a]/20 bg-[#ffdad6] p-5 text-sm text-[#93000a]"
        >
          {request.error instanceof ApiRequestError && request.error.status === 403 ? "لا تملك صلاحية عرض هذه البيانات." : t.error}
        </div>
      )}
      {request.data !== undefined && (
        <article className="overflow-hidden rounded-xl bg-white shadow-sm">
          <DataView data={request.data} detailHrefPrefix={detailGroup ? `/records/${detailGroup}` : undefined} />
        </article>
      )}
    </section>
  );
}
