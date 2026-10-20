"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { ArrowRight, Download, ExternalLink } from "lucide-react";
import { apiRequest } from "@/lib/api/client";
import { DataView } from "@/components/data-view";
import { operations } from "@/lib/api/operation-definitions";
import { useSystemAdminAccess } from "@/components/admin-link";
import { ProjectDeleteAction } from "@/components/project-delete-action";
import { PackageImportWorkflow } from "@/components/package-import-workflow";

const resourceNames: Record<string, string> = {
  projects: "المشروع", organizations: "المؤسسة", users: "المستخدم", roles: "الدور", packages: "الحزمة",
  migrationSources: "مصدر الترحيل", migrationBatches: "دفعة الترحيل", exportPackages: "حزمة التصدير",
  confidentialityLevels: "مستوى السرية", projectTypes: "نوع المشروع", fileCategories: "فئة الملفات", fileTypes: "نوع الملف", files: "الملف", audit: "سجل التدقيق",
};
const listRoutes: Record<string, string> = {
  projects: "/projects", organizations: "/organizations", users: "/users", roles: "/roles", packages: "/packages",
  migrationSources: "/migrations", exportPackages: "/exports", confidentialityLevels: "/settings/confidentiality-levels",
  projectTypes: "/settings/project-types", fileCategories: "/settings/file-categories", fileTypes: "/settings/file-types", audit: "/audit-logs",
};
const related: Record<string, { title: string; endpoint: string; detailGroup?: string }[]> = {
  projects: [
    { title: "أعضاء المشروع", endpoint: "projectMembers.list" },
    { title: "ملفات المشروع", endpoint: "files.list", detailGroup: "files" },
    { title: "نسخ المشروع", endpoint: "projects.versions" },
  ],
  users: [{ title: "أدوار المستخدم", endpoint: "users.roles" }],
  packages: [{ title: "مشاريع الحزمة", endpoint: "packages.projects" }, { title: "عمليات الحزمة", endpoint: "packages.operations" }],
  migrationSources: [{ title: "دفعات الترحيل", endpoint: "migrationBatches.list", detailGroup: "migrationBatches" }],
  organizations: [{ title: "الشجرة الفرعية", endpoint: "organizations.subtree" }, { title: "مسار المؤسسة", endpoint: "organizations.path" }],
};

function projectStatus(response: unknown): unknown {
  if (typeof response !== "object" || response === null || !("data" in response)) return undefined;
  const data = response.data;
  return typeof data === "object" && data !== null && "status" in data ? data.status : undefined;
}

function packageIsVerified(response: unknown): boolean {
  if (typeof response !== "object" || response === null || !("data" in response)) return false;
  const data = response.data;
  return typeof data === "object" && data !== null && ("verifiedAt" in data && Boolean(data.verifiedAt) || "status" in data && data.status === "VERIFIED");
}

function RelatedList({ title, endpoint, id, detailGroup }: { title: string; endpoint: string; id: string; detailGroup?: string }) {
  const request = useQuery({ queryKey: [endpoint, id], queryFn: () => apiRequest<unknown>(endpoint, "GET", {}, undefined, { id }) });
  const rowActions = endpoint === "projectMembers.list" ? (row: Record<string, unknown>) => row.id === undefined ? [] : [
    { label: "تعديل", href: `/operations/projectMembers.update?id=${encodeURIComponent(id)}&memberId=${encodeURIComponent(String(row.id))}` },
    { label: "حذف", href: `/operations/projectMembers.delete?id=${encodeURIComponent(id)}&memberId=${encodeURIComponent(String(row.id))}` },
  ] : endpoint === "users.roles" ? (row: Record<string, unknown>) => row.id === undefined ? [] : [
    { label: "إزالة الدور", href: `/operations/users.removeRole?id=${encodeURIComponent(id)}&roleId=${encodeURIComponent(String(row.id))}` },
  ] : undefined;
  return <section className="overflow-hidden rounded-xl bg-white shadow-sm"><h2 className="border-b border-[#eae8dd] px-5 py-4 font-bold">{title}</h2>{request.isLoading && <p className="p-5 text-sm text-[#717977]">جارٍ التحميل…</p>}{request.isError && <p role="alert" className="p-5 text-sm text-[#93000a]">تعذر تحميل البيانات.</p>}{request.data !== undefined && <DataView data={request.data} detailHrefPrefix={detailGroup ? `/records/${detailGroup}` : undefined} rowActions={rowActions} />}</section>;
}

export function RecordDetail({ group, id }: { group: string; id: string }) {
  const adminAccess = useSystemAdminAccess(["migrationSources", "migrationBatches"].includes(group));
  const request = useQuery({ queryKey: [`${group}.get`, id], queryFn: () => apiRequest<unknown>(`${group}.get`, "GET", {}, undefined, { id }) });
  const actions = operations.filter((operation) => operation.group === group && operation.pathParams.includes("id") && operation.key !== `${group}.create` && (adminAccess !== false || !["migrationSources", "migrationBatches"].includes(operation.group)));
  const nestedActions = group === "projects" ? operations.filter((operation) => ["projectMembers.create", "files.upload", "files.uploadBatch"].includes(operation.key))
    : group === "migrationSources" && adminAccess !== false ? operations.filter((operation) => operation.key === "migrationBatches.create") : [];
  const downloads = group === "files" ? [{ key: "files.download", label: "تنزيل الملف" }]
    : group === "exportPackages" ? [{ key: "exportPackages.manifest", label: "تنزيل البيان" }, { key: "exportPackages.download", label: "تنزيل الحزمة" }] : [];
  return <section className="space-y-5">
    <header className="rounded-xl bg-white p-6 shadow-sm"><Link href={listRoutes[group] ?? "/operations"} className="inline-flex items-center gap-2 text-sm text-[#33675d]"><ArrowRight size={16} aria-hidden="true" />العودة إلى القائمة</Link><h1 className="mt-3 text-2xl font-bold">تفاصيل {resourceNames[group] ?? "السجل"}</h1><p className="mt-1 font-mono text-xs text-[#717977]" dir="ltr">{id}</p></header>
    {request.isLoading && <div className="rounded-xl bg-white p-8 text-sm text-[#717977]">جارٍ التحميل…</div>}
    {request.isError && <p role="alert" className="rounded-xl bg-[#ffdad6] p-5 text-sm text-[#93000a]">تعذر تحميل السجل.</p>}
    {request.data !== undefined && <article className="overflow-hidden rounded-xl bg-white shadow-sm"><DataView data={request.data} /></article>}
    {group === "projects" && request.data !== undefined && <ProjectDeleteAction id={id} status={projectStatus(request.data)} />}
    {group === "packages" && request.data !== undefined && <PackageImportWorkflow packageId={id} initiallyVerified={packageIsVerified(request.data)} />}
    {(actions.length > 0 || nestedActions.length > 0) && <section className="rounded-xl bg-white p-5 shadow-sm"><h2 className="font-bold">الإجراءات المتاحة</h2><div className="mt-4 flex flex-wrap gap-2">{[...actions, ...nestedActions].map((operation) => <Link key={operation.key} href={`/operations/${operation.key}?id=${encodeURIComponent(id)}`} className="inline-flex items-center gap-2 rounded-lg border border-[#c1c8c6] bg-[#f6f4e9] px-3 py-2 text-sm text-[#002623] transition hover:border-[#33675d]"><span>{operation.title}</span><ExternalLink size={14} aria-hidden="true" /></Link>)}</div></section>}
    {downloads.length > 0 && <section className="flex flex-wrap gap-2 rounded-xl bg-white p-5 shadow-sm">{downloads.map((item) => <a key={item.key} href={`/api/gateway/${item.key}?param=${encodeURIComponent(`id=${id}`)}`} className="inline-flex items-center gap-2 rounded-lg bg-[#002623] px-4 py-2 text-sm font-semibold text-white"><Download size={16} aria-hidden="true" />{item.label}</a>)}</section>}
    {related[group]?.map((item) => <RelatedList key={item.endpoint} {...item} id={id} />)}
  </section>;
}
