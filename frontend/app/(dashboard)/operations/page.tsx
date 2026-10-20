"use client";

import Link from "next/link";
import { ArrowLeft, Plus } from "lucide-react";
import { operations } from "@/lib/api/operation-definitions";
import { useSystemAdminAccess } from "@/components/admin-link";

const groupNames: Record<string, string> = {
  auth: "الحساب", organizations: "المؤسسات", roles: "الأدوار", users: "المستخدمون",
  confidentialityLevels: "مستويات السرية", projectTypes: "أنواع المشاريع", fileCategories: "فئات الملفات", fileTypes: "أنواع الملفات",
  projects: "المشاريع", projectMembers: "أعضاء المشاريع", files: "الملفات", packages: "الحزم",
  migrationSources: "مصادر الترحيل", migrationBatches: "دفعات الترحيل", exportPackages: "حزم التصدير",
};

export default function OperationsPage() {
  const adminAccess = useSystemAdminAccess();
  const visible = adminAccess === false ? operations.filter((operation) => !["migrationSources", "migrationBatches"].includes(operation.group)) : operations;
  const groups = visible.reduce<Record<string, typeof operations>>((result, operation) => {
    (result[operation.group] ??= []).push(operation);
    return result;
  }, {});
  return <section className="space-y-6">
    <header className="rounded-xl bg-white p-6 shadow-sm"><div className="flex items-center gap-3"><Plus size={24} className="text-[#33675d]" aria-hidden="true" /><h1 className="text-2xl font-bold">الإجراءات</h1></div><Link href="/queries" className="mt-3 inline-flex items-center gap-2 text-sm font-semibold text-[#33675d]">البحث والاستعلام <ArrowLeft size={16} aria-hidden="true" /></Link></header>
    {Object.entries(groups).map(([group, items]) => <section key={group} className="overflow-hidden rounded-xl bg-white shadow-sm"><h2 className="border-b border-[#eae8dd] px-5 py-4 font-bold text-[#002623]">{groupNames[group] ?? group}</h2><div className="grid gap-2 p-4 sm:grid-cols-2 lg:grid-cols-3">{items?.map((operation) => <Link key={operation.key} href={`/operations/${operation.key}`} className="flex items-center justify-between gap-2 rounded-lg border border-[#e5e3d8] bg-[#fcfaee] px-4 py-3 text-sm text-[#414847] transition hover:border-[#33675d] hover:text-[#002623]"><span>{operation.title}</span><ArrowLeft size={16} aria-hidden="true" /></Link>)}</div></section>)}
  </section>;
}
