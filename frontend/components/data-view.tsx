"use client";

import { useMemo, useState } from "react";

type DataRecord = Record<string, unknown>;

const labels: Record<string, string> = {
  id: "المعرّف", code: "الرمز", name: "الاسم", fullName: "الاسم الكامل",
  username: "اسم المستخدم", email: "البريد الإلكتروني", description: "الوصف",
  isActive: "الحالة", status: "الحالة", createdAt: "تاريخ الإنشاء",
  updatedAt: "آخر تحديث", usersCount: "عدد المستخدمين", projectNumber: "رقم المشروع",
  projectCode: "رمز المشروع", year: "السنة", executingEntity: "الجهة المنفذة",
  supervisorName: "المشرف", sourceType: "نوع المصدر", organizationName: "المؤسسة",
  personName: "الاسم", roleName: "الدور", projectsCount: "عدد المشاريع",
  filesCount: "عدد الملفات", levelOrder: "ترتيب المستوى", defaultExtension: "الامتداد الافتراضي",
};
const listKeys = ["items", "content", "records", "results", "rows", "list"];
const metadataKeys = new Set(["page", "size", "limit", "total", "totalPages", "totalElements", "number", "numberOfElements", "first", "last"]);

function isRecord(value: unknown): value is DataRecord { return typeof value === "object" && value !== null && !Array.isArray(value); }
function humanize(key: string) { return labels[key] ?? key.replace(/([a-z0-9])([A-Z])/g, "$1 $2").replaceAll("_", " "); }

function extract(input: unknown) {
  const envelope = isRecord(input) && "data" in input ? input.data : input;
  if (Array.isArray(envelope)) return { rows: envelope.filter(isRecord), detail: null, metadata: isRecord(input) ? input : {} };
  if (!isRecord(envelope)) return { rows: [], detail: null, metadata: {} };
  for (const key of listKeys) {
    const candidate = envelope[key];
    if (Array.isArray(candidate)) return { rows: candidate.filter(isRecord), detail: null, metadata: envelope };
  }
  const arrayEntry = Object.entries(envelope).find(([, value]) => Array.isArray(value) && value.every(isRecord));
  if (arrayEntry) return { rows: (arrayEntry[1] as unknown[]).filter(isRecord), detail: null, metadata: envelope };
  return { rows: [], detail: envelope, metadata: envelope };
}

function DisplayValue({ name, value }: { name: string; value: unknown }) {
  if (value === null || value === undefined || value === "") return <span className="text-[#9a9e9c]">—</span>;
  if (typeof value === "boolean") return <span className={`inline-flex rounded-full px-2 py-1 text-[11px] font-semibold ${value ? "bg-[#b4ebdd] text-[#184f46]" : "bg-[#e5e3d8] text-[#414847]"}`}>{value ? "نشط" : "غير نشط"}</span>;
  if (typeof value === "string" && /(At|Date|Time)$/i.test(name) && !Number.isNaN(Date.parse(value))) return <span>{new Intl.DateTimeFormat("ar-SA", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value))}</span>;
  if (Array.isArray(value)) return <span>{value.length === 0 ? "—" : value.every((item) => typeof item === "string") ? value.join("، ") : `${value.length} عناصر`}</span>;
  if (isRecord(value)) return <span className="text-[#717977]">بيانات مرتبطة</span>;
  const text = String(value);
  if (name === "id" || /Id$/.test(name)) return <span className="font-mono text-[11px]" title={text}>{text.length > 14 ? `${text.slice(0, 8)}…${text.slice(-4)}` : text}</span>;
  return <span title={text}>{text}</span>;
}

export function DataView({ data }: { data: unknown }) {
  const [search, setSearch] = useState("");
  const extracted = useMemo(() => extract(data), [data]);
  const columns = useMemo(() => Array.from(new Set(extracted.rows.flatMap((row) => Object.keys(row)))).filter((key) => !metadataKeys.has(key)), [extracted.rows]);
  const rows = useMemo(() => {
    const term = search.trim().toLocaleLowerCase("ar");
    if (!term) return extracted.rows;
    return extracted.rows.filter((row) => Object.values(row).some((value) => typeof value !== "object" && String(value ?? "").toLocaleLowerCase("ar").includes(term)));
  }, [extracted.rows, search]);

  if (extracted.detail) {
    const entries = Object.entries(extracted.detail).filter(([key]) => !metadataKeys.has(key) && key !== "success");
    return <div className="grid gap-3 p-5 sm:grid-cols-2 xl:grid-cols-3">{entries.map(([key, value]) => <div key={key} className="rounded-lg border border-[#e5e3d8] bg-[#fcfaee] p-4"><p className="text-xs font-semibold text-[#717977]">{humanize(key)}</p><div className="mt-2 text-sm font-medium text-[#1b1c15]"><DisplayValue name={key} value={value} /></div></div>)}</div>;
  }

  const page = extracted.metadata.page ?? extracted.metadata.number;
  const total = extracted.metadata.total ?? extracted.metadata.totalElements;
  const totalPages = extracted.metadata.totalPages;
  return <div><div className="flex flex-col gap-3 border-b border-[#e5e3d8] p-4 sm:flex-row sm:items-center sm:justify-between"><div><p className="text-sm font-bold text-[#1b1c15]">السجلات المتاحة</p><p className="mt-1 text-xs text-[#717977]">{typeof total === "number" ? `${total} سجل` : `${extracted.rows.length} سجل`}</p></div><label className="block sm:w-72"><span className="sr-only">بحث في النتائج</span><input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="ابحث في النتائج…" className="w-full rounded-lg border border-[#c1c8c6] bg-[#f6f4e9] px-4 py-2 text-sm outline-none transition focus:border-[#33675d] focus:bg-white focus:ring-1 focus:ring-[#33675d]" /></label></div>
    {rows.length === 0 ? <div className="p-12 text-center text-sm text-[#717977]">لا توجد بيانات مطابقة للعرض.</div> : <div className="overflow-x-auto"><table className="w-full min-w-max text-right text-sm"><thead className="bg-[#f0eee3] text-xs text-[#414847]"><tr>{columns.map((column) => <th key={column} scope="col" className="whitespace-nowrap border-b border-[#dcdacf] px-4 py-3 font-semibold">{humanize(column)}</th>)}</tr></thead><tbody className="divide-y divide-[#eae8dd]">{rows.map((row, index) => <tr key={typeof row.id === "string" ? row.id : index} className="bg-white transition hover:bg-[#f6f4e9]">{columns.map((column) => <td key={column} className={`max-w-xs px-4 py-3 align-top text-[#3d3a3b] ${column === "name" || column === "fullName" ? "font-semibold text-[#1b1c15]" : ""}`}><DisplayValue name={column} value={row[column]} /></td>)}</tr>)}</tbody></table></div>}
    {(page !== undefined || totalPages !== undefined) && <div className="flex items-center justify-between border-t border-[#e5e3d8] bg-[#fcfaee] px-5 py-3 text-xs text-[#717977]"><span>الصفحة {String(page ?? 1)}</span>{totalPages !== undefined && <span>من {String(totalPages)} صفحات</span>}</div>}
  </div>;
}
