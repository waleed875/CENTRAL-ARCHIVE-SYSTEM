"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import { useMutation } from "@tanstack/react-query";
import { ArrowRight, Download, Search } from "lucide-react";
import { apiRequest } from "@/lib/api/client";
import type { QueryDefinition } from "@/lib/api/query-definitions";
import { DataView } from "@/components/data-view";
import { ReferenceSelect } from "@/components/reference-select";

const labels: Record<string, string> = {
  id: "معرّف السجل", entityType: "نوع الكيان", search: "بحث", searchTerm: "عبارة البحث", organizationId: "المؤسسة",
  categoryId: "فئة الملف", typeId: "نوع الملف", projectTypeId: "نوع المشروع", confidentialityLevelId: "مستوى السرية",
  parentId: "المؤسسة الأم", sourceOrganizationId: "المؤسسة المصدر", userId: "المستخدم", role: "الدور", status: "الحالة", year: "السنة", page: "الصفحة", size: "حجم الصفحة",
  limit: "الحد الأقصى", isActive: "نشط", isLatestVersion: "النسخة الأخيرة فقط", sort: "الترتيب حسب", order: "الاتجاه", sortBy: "الترتيب حسب", sortDirection: "الاتجاه",
  startDate: "من تاريخ", endDate: "إلى تاريخ", entityId: "معرّف الكيان", action: "الإجراء", sourceType: "نوع المصدر",
  createdBy: "أنشأه", approvedBy: "اعتمده", projectUid: "معرّف المشروع",
};

export function QueryForm({ definition, initialParams = {} }: { definition: QueryDefinition; initialParams?: Record<string, string> }) {
  const [values, setValues] = useState<Record<string, string>>(initialParams);
  const [error, setError] = useState("");
  const request = useMutation({ mutationFn: ({ query, pathParams }: { query: Record<string, string>; pathParams: Record<string, string> }) => apiRequest<unknown>(definition.key, "GET", query, undefined, pathParams) });
  const update = (name: string, value: string) => setValues((current) => ({
    ...current,
    [name]: value,
    ...(name === "categoryId" ? { typeId: "" } : {}),
  }));
  const buildParams = () => {
    const pathParams: Record<string, string> = {};
    const query: Record<string, string> = {};
    for (const name of definition.pathParams) {
      const value = (values[name] ?? "").trim();
      if (!value) return null;
      pathParams[name] = value;
    }
    for (const name of definition.queryParams) {
      const value = (values[name] ?? "").trim();
      if (value) query[name] = value;
    }
    return { pathParams, query };
  };
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const params = buildParams();
    if (!params) { setError("أدخل معرّف السجل المطلوب."); return; }
    setError("");
    request.mutate(params);
  };
  const downloadParams = definition.download ? buildParams() : null;
  const downloadUrl = downloadParams ? (() => {
    const search = new URLSearchParams(downloadParams.query);
    for (const [name, value] of Object.entries(downloadParams.pathParams)) search.append("param", `${name}=${value}`);
    return `/api/gateway/${definition.key}?${search}`;
  })() : null;

  return <section className="mx-auto max-w-4xl space-y-5">
    <header className="rounded-xl bg-white p-6 shadow-sm"><Link href="/queries" className="inline-flex items-center gap-2 text-sm text-[#33675d]"><ArrowRight size={16} aria-hidden="true" />كل الاستعلامات</Link><h1 className="mt-3 text-2xl font-bold">{definition.title}</h1></header>
    <form onSubmit={submit} className="rounded-xl bg-white p-6 shadow-sm"><div className="grid gap-4 sm:grid-cols-2">{[...definition.pathParams, ...definition.queryParams].map((name) => {
      const source = definition.optionSources[name];
      const label = labels[name] ?? name;
      const inputClass = "mt-1.5 w-full rounded-lg border border-[#c1c8c6] bg-white px-3 py-2.5 text-sm outline-none focus:border-[#33675d] focus:ring-1 focus:ring-[#33675d]";
      const required = definition.pathParams.includes(name);
      return <label key={name} className="text-sm font-medium text-[#414847]">{label}{required && <span className="mr-1 text-[#ba1a1a]">*</span>}
        {source
          ? <ReferenceSelect source={source} value={values[name] ?? ""} onChange={(value) => update(name, value)} label={label} className={inputClass} dependsOnValue={values[source.dependsOn?.field ?? ""] ?? ""} />
          : ["isActive", "isLatestVersion"].includes(name)
            ? <select value={values[name] ?? ""} onChange={(event) => update(name, event.target.value)} className={inputClass}><option value="">الكل</option><option value="true">نعم</option><option value="false">لا</option></select>
            : <input type={["page", "size", "limit", "year"].includes(name) ? "number" : ["startDate", "endDate"].includes(name) ? "date" : "text"} value={values[name] ?? ""} onChange={(event) => update(name, event.target.value)} required={required} className={inputClass} />}
      </label>;
    })}</div>
      {error && <p role="alert" className="mt-4 rounded-lg bg-[#ffdad6] p-3 text-sm text-[#93000a]">{error}</p>}
      <div className="mt-5">{definition.download ? downloadUrl ? <a href={downloadUrl} className="inline-flex items-center gap-2 rounded-lg bg-[#002623] px-5 py-2.5 text-sm font-semibold text-white"><Download size={16} aria-hidden="true" />تنزيل</a> : <span className="text-sm text-[#717977]">أدخل معرّف السجل للتنزيل.</span> : <button type="submit" disabled={request.isPending} className="inline-flex items-center gap-2 rounded-lg bg-[#002623] px-5 py-2.5 text-sm font-semibold text-white disabled:opacity-60"><Search size={16} aria-hidden="true" />{request.isPending ? "جارٍ التحميل…" : "عرض النتائج"}</button>}</div>
    </form>
    {request.isError && <p role="alert" className="rounded-xl bg-[#ffdad6] p-5 text-sm text-[#93000a]">{request.error.message}</p>}
    {request.data !== undefined && <article className="overflow-hidden rounded-xl bg-white shadow-sm"><DataView data={request.data} /></article>}
  </section>;
}
