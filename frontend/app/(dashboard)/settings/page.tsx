"use client";

import Link from "next/link";
import { FileStack, FileType2, FolderKanban, ShieldCheck, Database } from "lucide-react";
import { useSystemAdminAccess } from "@/components/admin-link";

const sections = [
  ["مستويات السرية", "إضافة المستويات وتعديلها.", ShieldCheck, "confidentiality-levels"],
  ["أنواع المشاريع", "إضافة أنواع المشاريع وتعديلها.", FolderKanban, "project-types"],
  ["فئات الملفات", "إضافة فئات الملفات وتعديلها.", FileStack, "file-categories"],
  ["أنواع الملفات", "إضافة أنواع الملفات وتعديلها.", FileType2, "file-types"],
  ["إعدادات الترحيل", "تشغيل الترحيل وكود السرية الافتراضي.", Database, "migration"],
] as const;

export default function Page() {
  const adminAccess = useSystemAdminAccess();
  return <section className="space-y-6"><header className="rounded-xl bg-white p-6 shadow-sm"><div className="flex items-center gap-3"><span className="h-7 w-2.5 rounded-full bg-[#000c0b]" /><h1 className="text-2xl font-bold">الإعدادات</h1></div><p className="mt-2 text-sm text-[#414847]">أنواع المشاريع والملفات ومستويات السرية.</p></header><div className="grid gap-4 sm:grid-cols-2">{sections.filter(([, , , slug]) => slug !== "migration" || adminAccess !== false).map(([title, description, Icon, slug]) => <article key={title} className="relative overflow-hidden rounded-xl bg-white p-6 shadow-sm"><span className="absolute -top-6 -left-6 h-24 w-24 rounded-full bg-[#33675d]/10" /><div className="relative"><span className="mb-4 flex h-9 w-9 items-center justify-center rounded-lg bg-[#f6f4e9] text-[#33675d]"><Icon size={20} strokeWidth={1.8} aria-hidden="true" /></span><h2 className="font-bold text-[#1b1c15]">{title}</h2><p className="mt-2 text-sm leading-6 text-[#414847]">{description}</p></div><Link className="relative mt-5 inline-flex rounded-md border border-[#c1c8c6] bg-[#f6f4e9] px-3 py-2 text-sm font-medium text-[#002623] transition hover:bg-[#eae8dd]" href={`/settings/${slug}`}>عرض وإدارة</Link></article>)}</div></section>;
}
