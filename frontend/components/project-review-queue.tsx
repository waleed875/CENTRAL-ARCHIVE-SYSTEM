"use client";

import Link from "next/link";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiRequestError, apiRequest } from "@/lib/api/client";
import { hasAnyRoleResponse } from "@/lib/api/permissions";
import { ReferenceSelect } from "@/components/reference-select";

type DataRecord = Record<string, unknown>;
const reviewRoles = ["auditor", "system_admin"];
const pageSize = 20;
const organizationSource = { endpoint: "organizations.all", labelStyle: "organization" as const };
const userSource = { endpoint: "users.list", pagination: { pageParam: "page", sizeParam: "limit", pageSize: 100 } };

function isRecord(value: unknown): value is DataRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function recordsFrom(value: unknown): DataRecord[] {
  const data = isRecord(value) && "data" in value ? value.data : value;
  if (Array.isArray(data) && data.every(isRecord)) return data;
  if (isRecord(data)) {
    for (const key of ["content", "items", "records", "results", "rows"]) {
      if (Array.isArray(data[key]) && (data[key] as unknown[]).every(isRecord)) return data[key] as DataRecord[];
    }
  }
  throw new Error("استجابة قائمة مراجعة المشاريع غير صالحة.");
}

function totalPagesFrom(value: unknown): number | undefined {
  const data = isRecord(value) && "data" in value ? value.data : value;
  const candidates = [isRecord(data) ? data : undefined, isRecord(value) ? value.meta : undefined, isRecord(data) ? data.meta : undefined];
  const pages = candidates.find((candidate) => isRecord(candidate) && typeof candidate.totalPages === "number" && Number.isInteger(candidate.totalPages));
  return isRecord(pages) && typeof pages.totalPages === "number" ? pages.totalPages : undefined;
}

function displayValue(value: unknown): string {
  if (value === null || value === undefined || value === "") return "—";
  if (typeof value !== "string") return String(value);
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString("ar");
}

export function ProjectReviewQueue() {
  const queryClient = useQueryClient();
  const [organizationId, setOrganizationId] = useState("");
  const [submittedBy, setSubmittedBy] = useState("");
  const [page, setPage] = useState(1);
  const [notesByProject, setNotesByProject] = useState<Record<string, string>>({});
  const currentUser = useQuery({ queryKey: ["auth.me"], queryFn: () => apiRequest<unknown>("auth.me", "GET") });
  const access = currentUser.data ? hasAnyRoleResponse(currentUser.data, reviewRoles) : null;
  const queue = useQuery({
    queryKey: ["projects.reviewQueue", organizationId, submittedBy, page],
    queryFn: async () => {
      const response = await apiRequest<unknown>("projects.reviewQueue", "GET", {
        organizationId: organizationId || undefined,
        submittedBy: submittedBy || undefined,
        page,
        size: pageSize,
      });
      return { records: recordsFrom(response), totalPages: totalPagesFrom(response) };
    },
    enabled: access === true,
  });
  const action = useMutation({
    mutationFn: ({ id, type, notes }: { id: string; type: "approve" | "reject" | "correction"; notes: string }) => {
      if (type === "reject" && !notes.trim()) throw new Error("أدخل سبب الرفض.");
      if (type === "correction" && !notes.trim()) throw new Error("أدخل ملاحظات التصحيح.");
      const key = type === "correction" ? "projects.correction" : `projects.${type}`;
      const body = type === "reject" ? { rejection_reason: notes.trim() } : type === "correction" ? { review_notes: notes.trim() } : undefined;
      return apiRequest<unknown>(key, "PATCH", {}, body, { id });
    },
    onSuccess: async (_data, variables) => {
      setNotesByProject((current) => ({ ...current, [variables.id]: "" }));
      await queryClient.invalidateQueries({ queryKey: ["projects.reviewQueue"] });
    },
  });
  const filterChanged = (setter: (value: string) => void) => (value: string) => {
    setter(value);
    setPage(1);
  };
  const runAction = (id: string, type: "approve" | "reject" | "correction") => {
    action.reset();
    action.mutate({ id, type, notes: notesByProject[id] ?? "" });
  };

  return <section className="space-y-5">
    <header className="rounded-xl bg-white p-6 shadow-sm">
      <h1 className="text-2xl font-bold">مراجعة المشاريع</h1>
      <p className="mt-1 text-sm text-[#717977]">قائمة المشاريع المنتظرة مرتبة من الأقدم إرسالًا.</p>
    </header>
    {currentUser.isLoading && <p className="rounded-xl bg-white p-5 text-sm text-[#717977]">جارٍ التحقق من الصلاحيات…</p>}
    {currentUser.isError && <p role="alert" className="rounded-xl bg-[#ffdad6] p-5 text-sm text-[#93000a]">تعذر التحقق من صلاحية المراجعة: {currentUser.error.message}</p>}
    {access === false && <p role="alert" className="rounded-xl bg-[#ffdad6] p-5 text-sm text-[#93000a]">تتطلب مراجعة المشاريع دور auditor أو system_admin.</p>}
    {access === null && currentUser.data !== undefined && <p role="alert" className="rounded-xl bg-[#ffdad6] p-5 text-sm text-[#93000a]">تعذر تحديد أدوار المستخدم؛ لم يتم تحميل قائمة المراجعة.</p>}
    {access === true && <>
      <div className="grid gap-4 rounded-xl bg-white p-5 shadow-sm sm:grid-cols-2">
        <label className="text-sm font-medium text-[#414847]">المؤسسة
          <ReferenceSelect source={organizationSource} value={organizationId} onChange={filterChanged(setOrganizationId)} label="المؤسسة" className="mt-1.5 w-full rounded-lg border border-[#c1c8c6] bg-white px-3 py-2.5 text-sm" />
        </label>
        <label className="text-sm font-medium text-[#414847]">المرسل
          <ReferenceSelect source={userSource} value={submittedBy} onChange={filterChanged(setSubmittedBy)} label="المرسل" className="mt-1.5 w-full rounded-lg border border-[#c1c8c6] bg-white px-3 py-2.5 text-sm" />
        </label>
      </div>
      {queue.isLoading && <p className="rounded-xl bg-white p-8 text-center text-sm text-[#717977]">جارٍ تحميل قائمة المراجعة…</p>}
      {queue.isError && <p role="alert" className="rounded-xl bg-[#ffdad6] p-5 text-sm text-[#93000a]">{queue.error instanceof ApiRequestError && queue.error.status === 403 ? "لا تملك صلاحية عرض قائمة المراجعة." : `تعذر تحميل قائمة المراجعة: ${queue.error.message}`}</p>}
      {action.isError && <p role="alert" className="rounded-xl bg-[#ffdad6] p-4 text-sm text-[#93000a]">{action.error.message}</p>}
      {queue.data && queue.data.records.length === 0 && <p className="rounded-xl bg-white p-8 text-center text-sm text-[#717977]">لا توجد مشاريع بانتظار المراجعة.</p>}
      {queue.data?.records.map((project, index) => {
        const id = typeof project.id === "string" ? project.id : "";
        const notes = notesByProject[id] ?? "";
        return <article key={id || index} className="space-y-4 rounded-xl bg-white p-5 shadow-sm">
          <header className="flex flex-wrap items-start justify-between gap-3">
            <div>
              {id ? <Link href={`/records/projects/${encodeURIComponent(id)}`} className="font-bold text-[#184f46] hover:underline">{String(project.name ?? project.projectNumber ?? "مشروع دون اسم")}</Link> : <p className="font-bold">مشروع دون معرّف</p>}
              <p className="mt-1 text-xs text-[#717977]">رقم المشروع: {displayValue(project.projectNumber)}</p>
            </div>
            <span className="rounded-full bg-[#f6f4e9] px-3 py-1 text-xs">{displayValue(project.status)}</span>
          </header>
          <dl className="grid gap-3 text-sm sm:grid-cols-2">
            <div><dt className="font-semibold">تاريخ الإرسال</dt><dd className="text-[#717977]">{displayValue(project.submittedAt)}</dd></div>
            <div><dt className="font-semibold">المرسل</dt><dd className="text-[#717977]">{displayValue(project.submittedByName ?? project.submittedBy)}</dd></div>
            <div><dt className="font-semibold">ملاحظات التصحيح</dt><dd className="whitespace-pre-wrap text-[#717977]">{displayValue(project.reviewNotes)}</dd></div>
            <div><dt className="font-semibold">سبب الرفض</dt><dd className="whitespace-pre-wrap text-[#717977]">{displayValue(project.rejectionReason)}</dd></div>
          </dl>
          {id && <div className="space-y-3 border-t border-[#eae8dd] pt-4">
            <label className="block text-sm font-medium text-[#414847]">سبب الرفض أو ملاحظات التصحيح
              <textarea value={notes} onChange={(event) => setNotesByProject((current) => ({ ...current, [id]: event.target.value }))} rows={2} className="mt-1.5 w-full rounded-lg border border-[#c1c8c6] px-3 py-2 text-sm" />
            </label>
            <div className="flex flex-wrap gap-2">
              <button type="button" disabled={action.isPending} onClick={() => runAction(id, "approve")} className="rounded-lg bg-[#184f46] px-4 py-2 text-sm font-semibold text-white disabled:opacity-50">موافقة</button>
              <button type="button" disabled={action.isPending} onClick={() => runAction(id, "reject")} className="rounded-lg bg-[#93000a] px-4 py-2 text-sm font-semibold text-white disabled:opacity-50">رفض</button>
              <button type="button" disabled={action.isPending} onClick={() => runAction(id, "correction")} className="rounded-lg border border-[#c1c8c6] px-4 py-2 text-sm font-semibold text-[#002623] disabled:opacity-50">طلب تصحيح</button>
              {action.isSuccess && action.variables?.id === id && <span role="status" className="self-center text-sm text-[#184f46]">تم إرسال الإجراء.</span>}
            </div>
          </div>}
        </article>;
      })}
      {queue.data && queue.data.records.length > 0 && <div className="flex items-center justify-between rounded-xl bg-white p-4 shadow-sm">
        <button type="button" disabled={page <= 1 || queue.isFetching} onClick={() => setPage((value) => Math.max(1, value - 1))} className="rounded-lg border border-[#c1c8c6] px-4 py-2 text-sm disabled:opacity-50">السابق</button>
        <span className="text-sm text-[#414847]">صفحة {page}{queue.data.totalPages !== undefined ? ` من ${queue.data.totalPages}` : ""}</span>
        <button type="button" disabled={queue.isFetching || (queue.data.totalPages !== undefined ? page >= queue.data.totalPages : queue.data.records.length < pageSize)} onClick={() => setPage((value) => value + 1)} className="rounded-lg border border-[#c1c8c6] px-4 py-2 text-sm disabled:opacity-50">التالي</button>
      </div>}
    </>}
  </section>;
}
