"use client";

import { useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { ArrowRight } from "lucide-react";
import { ApiRequestError, apiRequest } from "@/lib/api/client";
import { incompleteProjectReportSchema } from "@/lib/api/contracts";

const fieldNames: Record<string, string> = {
  year: "السنة",
  name: "اسم المشروع",
  projectNumber: "رقم المشروع",
  projectCode: "رمز المشروع",
  organizationId: "الجهة",
  officeName: "المكتب",
};
const statusNames: Record<string, string> = {
  DRAFT: "مسودة",
  IMPORTED: "مستورد",
  UNDER_REVIEW: "قيد المراجعة",
  NEEDS_CORRECTION: "يحتاج تصحيحاً",
  APPROVED: "معتمد",
  REJECTED: "مرفوض",
  ARCHIVED: "مؤرشف",
};
function readReport(value: unknown) {
  const data =
    value && typeof value === "object" && "data" in value ? value.data : value;
  return incompleteProjectReportSchema.parse(data);
}

export default function IncompleteReportPage() {
  const [page, setPage] = useState(1);
  const report = useQuery({
    queryKey: ["projects.incompleteReport"],
    queryFn: async () =>
      readReport(await apiRequest<unknown>("projects.incompleteReport", "GET")),
  });
  const forbidden =
    report.error instanceof ApiRequestError && report.error.status === 403;
  const pageSize = 25;
  const totalPages = Math.max(
    1,
    Math.ceil((report.data?.projects.length ?? 0) / pageSize),
  );
  const currentPage = Math.min(page, totalPages);
  return (
    <section className="space-y-5">
      <header className="rounded-xl bg-white p-6 shadow-sm">
        <Link
          href="/projects"
          className="inline-flex items-center gap-2 text-sm text-[#33675d]"
        >
          <ArrowRight size={16} aria-hidden="true" />
          المشاريع
        </Link>
        <h1 className="mt-3 text-2xl font-bold">
          المشاريع ذات البيانات الناقصة
        </h1>
      </header>
      {report.isLoading && (
        <p className="rounded-xl bg-white p-6 text-sm">جارٍ التحميل…</p>
      )}
      {report.isError && (
        <p
          role="alert"
          className="rounded-xl bg-[#ffdad6] p-5 text-sm text-[#93000a]"
        >
          {forbidden ? "لا تملك صلاحية عرض التقرير." : "تعذر تحميل التقرير."}
        </p>
      )}
      {report.data && (
        <>
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="rounded-xl bg-white p-5 shadow-sm">
              <p className="text-sm text-[#717977]">إجمالي المشاريع الناقصة</p>
              <p className="mt-2 text-3xl font-bold">
                {report.data.totalIncomplete}
              </p>
            </div>
            <div className="rounded-xl bg-white p-5 shadow-sm">
              <p className="text-sm text-[#717977]">سنة غير معروفة</p>
              <p className="mt-2 text-3xl font-bold">
                {report.data.totalWithUnknownYear}
              </p>
            </div>
          </div>
          <section className="overflow-hidden rounded-xl bg-white shadow-sm">
            <h2 className="border-b border-[#e5e3d8] p-5 font-bold">
              المشاريع
            </h2>
            {report.data.projects.length === 0 ? (
              <p className="p-6 text-sm text-[#717977]">
                لا توجد مشاريع ببيانات ناقصة.
              </p>
            ) : (
              <>
                <div className="overflow-x-auto">
                  <table className="w-full min-w-[900px] text-right text-sm">
                    <thead className="bg-[#002623] text-white">
                      <tr>
                        {[
                          "المشروع",
                          "رقم المشروع",
                          "المؤسسة",
                          "السنة",
                          "الحالة",
                          "الحقول الناقصة",
                          "المصدر",
                        ].map((label) => (
                          <th
                            key={label}
                            scope="col"
                            className="px-4 py-3 font-semibold"
                          >
                            {label}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-[#eae8dd]">
                      {report.data.projects
                        .slice(
                          (currentPage - 1) * pageSize,
                          currentPage * pageSize,
                        )
                        .map((project) => (
                          <tr key={project.id}>
                            <td className="px-4 py-3">
                              <Link
                                href={`/records/projects/${encodeURIComponent(project.id)}`}
                                className="font-semibold text-[#33675d] hover:underline"
                              >
                                {project.name || "مشروع بلا اسم"}
                              </Link>
                            </td>
                            <td className="px-4 py-3">
                              {project.projectNumber || "—"}
                            </td>
                            <td className="px-4 py-3">
                              {project.organizationName || "—"}
                            </td>
                            <td className="px-4 py-3">
                              {project.year === 1000
                                ? "سنة غير معروفة"
                                : (project.year ?? "—")}
                            </td>
                            <td className="px-4 py-3">
                              {project.status
                                ? (statusNames[project.status] ??
                                  project.status)
                                : "—"}
                            </td>
                            <td className="px-4 py-3">
                              {project.missingFields
                                .map((field) => fieldNames[field] ?? field)
                                .join("، ") || "—"}
                            </td>
                            <td className="px-4 py-3">
                              {project.sourceReference || "—"}
                            </td>
                          </tr>
                        ))}
                    </tbody>
                  </table>
                </div>
                {totalPages > 1 && (
                  <div className="flex items-center justify-between border-t border-[#e5e3d8] p-4 text-sm">
                    <button
                      type="button"
                      disabled={currentPage === 1}
                      onClick={() => setPage(currentPage - 1)}
                      className="rounded border px-3 py-1 disabled:opacity-40"
                    >
                      السابق
                    </button>
                    <span>
                      الصفحة {currentPage} من {totalPages}
                    </span>
                    <button
                      type="button"
                      disabled={currentPage === totalPages}
                      onClick={() => setPage(currentPage + 1)}
                      className="rounded border px-3 py-1 disabled:opacity-40"
                    >
                      التالي
                    </button>
                  </div>
                )}
              </>
            )}
          </section>
          <section className="rounded-xl bg-white p-5 shadow-sm">
            <h2 className="font-bold">الحقول الناقصة</h2>
            {report.data.fieldCounts.length === 0 ? (
              <p className="mt-3 text-sm text-[#717977]">لا توجد بيانات.</p>
            ) : (
              <div className="mt-4 flex flex-wrap gap-2">
                {report.data.fieldCounts.map((item) => (
                  <span
                    key={item.field}
                    className="rounded-lg bg-[#f6f4e9] px-3 py-2 text-sm"
                  >
                    {fieldNames[item.field] ?? item.field}:{" "}
                    <strong>{item.count}</strong>
                  </span>
                ))}
              </div>
            )}
          </section>
        </>
      )}
    </section>
  );
}
