"use client";

import { FormEvent, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiRequest } from "@/lib/api/client";

type ApiEnvelope<T> = {
  success?: boolean;
  data?: T;
  meta?: { message?: string };
};

type Paged<T> = {
  data: T[];
  page: number;
  limit: number;
  total: number;
  totalPages: number;
};

type PackageRow = {
  id: string;
  packageUuid: string;
  sourceOrganizationId?: string | null;
  sourceTerminalUuid?: string | null;
  sourceUsername?: string | null;
  packageName: string;
  schemaVersion?: string | null;
  projectsCount?: number | null;
  filesCount?: number | null;
  status: string;
  receivedAt?: string | null;
  verifiedAt?: string | null;
  importedAt?: string | null;
  errorMessage?: string | null;
};

type Organization = {
  id: string;
  name: string;
  code?: string | null;
  isActive?: boolean | null;
};

type VerifyResult = {
  packageId: string;
  packageUuid: string;
  valid: boolean;
  manifestHashValid: boolean;
  packageIntegrityValid: boolean;
  schemaVersionValid: boolean;
  manifestProjectsCount?: number | null;
  manifestFilesCount?: number | null;
  verifiedAt?: string | null;
  errors?: string[];
  warnings?: string[];
  totalSizeBytes?: number | null;
};

type OperationSummary = {
  packageId?: string;
  packageUuid?: string;
  status?: string;
  receivedAt?: string | null;
  verifiedAt?: string | null;
  importedAt?: string | null;
  errorMessage?: string | null;
  importOperations?: Array<Record<string, unknown>>;
  projectStats?: {
    total?: number;
    imported?: number;
    skipped?: number;
    failed?: number;
    duplicate?: number;
  };
  manifestProjectsCount?: number;
  totalFilesCount?: number;
};

function unwrap<T>(value?: ApiEnvelope<T>): T | undefined {
  return value?.data;
}

function formatDate(value?: string | null) {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat("ar", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(date);
}

function statusClass(status?: string) {
  switch (status) {
    case "VERIFIED":
      return "bg-blue-100 text-blue-800";
    case "IMPORTED":
      return "bg-emerald-100 text-emerald-800";
    case "FAILED":
      return "bg-red-100 text-red-800";
    case "DUPLICATE":
      return "bg-amber-100 text-amber-800";
    default:
      return "bg-slate-100 text-slate-700";
  }
}

export default function PackagesPage() {
  const queryClient = useQueryClient();
  const [selectedId, setSelectedId] = useState<string>("");
  const [targetOrganizationId, setTargetOrganizationId] = useState("");
  const [confidentialityLevelId, setConfidentialityLevelId] = useState("");
  const [uploadFile, setUploadFile] = useState<File | null>(null);

  const packagesQuery = useQuery({
    queryKey: ["packages.list"],
    queryFn: () =>
      apiRequest<ApiEnvelope<Paged<PackageRow>>>("packages.list", "GET", {
        page: 1,
        size: 50,
      }),
  });

  const organizationsQuery = useQuery({
    queryKey: ["organizations.list", "package-import"],
    queryFn: () =>
      apiRequest<ApiEnvelope<Paged<Organization>>>("organizations.list", "GET", {
        page: 1,
        size: 200,
        isActive: "true",
      }),
  });

  const packages = useMemo(
    () => unwrap(packagesQuery.data)?.data ?? [],
    [packagesQuery.data],
  );
  const organizations = useMemo(
    () => unwrap(organizationsQuery.data)?.data ?? [],
    [organizationsQuery.data],
  );
  const selectedPackage = useMemo(
    () => packages.find((item) => item.id === selectedId),
    [packages, selectedId],
  );

  const verifyQuery = useQuery({
    queryKey: ["packages.verify-result", selectedId],
    enabled: false,
    queryFn: () =>
      apiRequest<ApiEnvelope<VerifyResult>>(
        "packages.verify",
        "POST",
        {},
        undefined,
        { id: selectedId },
      ),
  });

  const operationsQuery = useQuery({
    queryKey: ["packages.operations", selectedId],
    enabled: Boolean(selectedId),
    refetchInterval: (query) => {
      const current = unwrap(query.state.data as ApiEnvelope<OperationSummary>);
      return current?.status === "IMPORTED" || current?.status === "FAILED"
        ? false
        : 5000;
    },
    queryFn: () =>
      apiRequest<ApiEnvelope<OperationSummary>>(
        "packages.operations",
        "GET",
        {},
        undefined,
        { id: selectedId },
      ),
  });

  const uploadMutation = useMutation({
    mutationFn: async () => {
      if (!uploadFile) throw new Error("PACKAGE_FILE_REQUIRED");
      const form = new FormData();
      form.append("package_file", uploadFile);
      return apiRequest<ApiEnvelope<PackageRow>>(
        "packages.receive",
        "POST",
        {},
        form,
      );
    },
    onSuccess: async (response) => {
      const created = unwrap(response);
      setUploadFile(null);
      if (created?.id) setSelectedId(created.id);
      await queryClient.invalidateQueries({ queryKey: ["packages.list"] });
    },
  });

  const importMutation = useMutation({
    mutationFn: async () => {
      if (!selectedPackage) throw new Error("PACKAGE_REQUIRED");
      if (!selectedPackage.sourceOrganizationId) {
        throw new Error("SOURCE_ORGANIZATION_REQUIRED");
      }
      if (!targetOrganizationId) {
        throw new Error("TARGET_ORGANIZATION_REQUIRED");
      }

      return apiRequest<ApiEnvelope<Record<string, string>>>(
        "packages.import",
        "POST",
        {},
        {
          organization_mapping: {
            [selectedPackage.sourceOrganizationId]: targetOrganizationId,
          },
          confidentiality_level_id: confidentialityLevelId || null,
        },
        { id: selectedPackage.id },
      );
    },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["packages.list"] }),
        queryClient.invalidateQueries({
          queryKey: ["packages.operations", selectedId],
        }),
      ]);
    },
  });

  async function verifySelected() {
    if (!selectedId) return;
    await verifyQuery.refetch();
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ["packages.list"] }),
      queryClient.invalidateQueries({
        queryKey: ["packages.operations", selectedId],
      }),
    ]);
  }

  function submitUpload(event: FormEvent) {
    event.preventDefault();
    uploadMutation.mutate();
  }

  const verifyResult = unwrap(verifyQuery.data);
  const operationSummary = unwrap(operationsQuery.data);

  return (
    <section className="space-y-6">
      <header className="rounded-xl bg-white p-6 shadow-sm">
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div>
            <div className="flex items-center gap-3">
              <span className="h-7 w-2.5 rounded-full bg-[#000c0b]" />
              <h1 className="text-2xl font-bold">حزم الفروع</h1>
            </div>
            <p className="mt-2 text-sm text-[#414847]">
              سير العمل المركزي: استلام الحزمة ثم التحقق منها ثم الاستيراد
              الصريح ومتابعة النتيجة.
            </p>
          </div>
          <span className="rounded-full bg-[#f5e1af] px-3 py-1 text-xs font-semibold text-[#4b3e1a]">
            RECEIVED → VERIFIED → IMPORTED
          </span>
        </div>
      </header>

      <form
        onSubmit={submitUpload}
        className="rounded-xl bg-white p-6 shadow-sm"
      >
        <h2 className="font-bold">1. استلام حزمة جديدة</h2>
        <p className="mt-1 text-sm text-[#717977]">
          اختر ملف الحزمة الفعلي بصيغة .pkg. يتم تسجيل المستخدم الحالي من
          جلسة JWT تلقائيًا.
        </p>
        <div className="mt-4 flex flex-col gap-3 sm:flex-row sm:items-center">
          <input
            type="file"
            accept=".pkg,application/octet-stream"
            onChange={(event) =>
              setUploadFile(event.target.files?.[0] ?? null)
            }
            className="block flex-1 rounded-lg border border-[#c1c8c6] bg-[#f6f4e9] px-3 py-2 text-sm"
          />
          <button
            type="submit"
            disabled={!uploadFile || uploadMutation.isPending}
            className="rounded-lg bg-[#002623] px-5 py-2.5 text-sm font-semibold text-white disabled:opacity-50"
          >
            {uploadMutation.isPending ? "جارٍ الرفع…" : "استلام الحزمة"}
          </button>
        </div>
        {uploadMutation.isError && (
          <p className="mt-3 rounded-lg bg-red-50 p-3 text-sm text-red-800">
            تعذر رفع الحزمة. تحقق من الملف وحالة MinIO وصلاحيات المستخدم.
          </p>
        )}
      </form>

      <div className="grid gap-6 xl:grid-cols-12">
        <article className="overflow-hidden rounded-xl bg-white shadow-sm xl:col-span-7">
          <div className="flex items-center justify-between border-b border-[#e5e3d8] p-5">
            <div>
              <h2 className="font-bold">2. الحزم المستلمة</h2>
              <p className="mt-1 text-xs text-[#717977]">
                اختر حزمة لإجراء التحقق والاستيراد.
              </p>
            </div>
            <button
              type="button"
              onClick={() => packagesQuery.refetch()}
              className="rounded-md border border-[#c1c8c6] px-3 py-2 text-xs"
            >
              تحديث
            </button>
          </div>

          {packagesQuery.isLoading && (
            <p className="p-8 text-center text-sm text-[#717977]">
              جارٍ تحميل الحزم…
            </p>
          )}

          {packagesQuery.isError && (
            <p className="p-8 text-center text-sm text-red-700">
              تعذر تحميل الحزم.
            </p>
          )}

          {!packagesQuery.isLoading && packages.length === 0 && (
            <p className="p-8 text-center text-sm text-[#717977]">
              لا توجد حزم مستلمة بعد.
            </p>
          )}

          {packages.length > 0 && (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[760px] text-right text-sm">
                <thead className="bg-[#f0eee3] text-xs">
                  <tr>
                    <th className="px-4 py-3">الحزمة</th>
                    <th className="px-4 py-3">المصدر</th>
                    <th className="px-4 py-3">المشاريع</th>
                    <th className="px-4 py-3">الملفات</th>
                    <th className="px-4 py-3">الحالة</th>
                    <th className="px-4 py-3">الاستلام</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-[#eae8dd]">
                  {packages.map((item) => (
                    <tr
                      key={item.id}
                      onClick={() => {
                        setSelectedId(item.id);
                        setTargetOrganizationId("");
                        setConfidentialityLevelId("");
                      }}
                      className={`cursor-pointer transition hover:bg-[#f6f4e9] ${selectedId === item.id ? "bg-[#e8f2ef]" : ""}`}
                    >
                      <td className="px-4 py-3">
                        <p className="font-semibold">{item.packageName}</p>
                        <p className="mt-1 font-mono text-[11px] text-[#717977]">
                          {item.packageUuid}
                        </p>
                      </td>
                      <td className="px-4 py-3">
                        {item.sourceUsername ?? "—"}
                      </td>
                      <td className="px-4 py-3">
                        {item.projectsCount ?? "—"}
                      </td>
                      <td className="px-4 py-3">{item.filesCount ?? "—"}</td>
                      <td className="px-4 py-3">
                        <span
                          className={`rounded-full px-2 py-1 text-xs font-semibold ${statusClass(item.status)}`}
                        >
                          {item.status}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        {formatDate(item.receivedAt)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </article>

        <aside className="space-y-5 xl:col-span-5">
          <article className="rounded-xl bg-white p-5 shadow-sm">
            <h2 className="font-bold">3. التحقق من الحزمة</h2>
            {!selectedPackage ? (
              <p className="mt-3 text-sm text-[#717977]">
                اختر حزمة من القائمة أولًا.
              </p>
            ) : (
              <>
                <div className="mt-4 space-y-2 rounded-lg bg-[#f6f4e9] p-4 text-sm">
                  <p>
                    <strong>الحالة:</strong> {selectedPackage.status}
                  </p>
                  <p>
                    <strong>الإصدار:</strong>{" "}
                    {selectedPackage.schemaVersion ?? "—"}
                  </p>
                  <p>
                    <strong>تم التحقق:</strong>{" "}
                    {formatDate(selectedPackage.verifiedAt)}
                  </p>
                  {selectedPackage.errorMessage && (
                    <p className="text-red-700">
                      {selectedPackage.errorMessage}
                    </p>
                  )}
                </div>
                <button
                  type="button"
                  onClick={verifySelected}
                  disabled={verifyQuery.isFetching}
                  className="mt-4 w-full rounded-lg bg-[#33675d] px-4 py-2.5 text-sm font-semibold text-white disabled:opacity-50"
                >
                  {verifyQuery.isFetching
                    ? "جارٍ التحقق…"
                    : "تحقق من سلامة الحزمة"}
                </button>
              </>
            )}

            {verifyResult && (
              <div
                className={`mt-4 rounded-lg p-4 text-sm ${verifyResult.valid ? "bg-emerald-50 text-emerald-900" : "bg-red-50 text-red-900"}`}
              >
                <p className="font-bold">
                  {verifyResult.valid
                    ? "الحزمة اجتازت التحقق"
                    : "فشل التحقق من الحزمة"}
                </p>
                <p className="mt-2">
                  المشاريع: {verifyResult.manifestProjectsCount ?? "—"} ·
                  الملفات: {verifyResult.manifestFilesCount ?? "—"}
                </p>
                {verifyResult.errors?.map((error) => (
                  <p key={error} className="mt-1">
                    • {error}
                  </p>
                ))}
                {verifyResult.warnings?.map((warning) => (
                  <p key={warning} className="mt-1 text-amber-800">
                    • {warning}
                  </p>
                ))}
              </div>
            )}
          </article>

          <article className="rounded-xl bg-white p-5 shadow-sm">
            <h2 className="font-bold">4. الاستيراد المركزي</h2>
            <p className="mt-1 text-xs text-[#717977]">
              الاستيراد لا يبدأ إلا بعد اختيار المؤسسة المركزية المستهدفة.
            </p>

            <label className="mt-4 block text-sm font-semibold">
              المؤسسة المركزية
              <select
                value={targetOrganizationId}
                onChange={(event) =>
                  setTargetOrganizationId(event.target.value)
                }
                disabled={!selectedPackage}
                className="mt-2 w-full rounded-lg border border-[#c1c8c6] bg-white px-3 py-2"
              >
                <option value="">اختر المؤسسة</option>
                {organizations.map((organization) => (
                  <option key={organization.id} value={organization.id}>
                    {organization.name}
                    {organization.code ? ` (${organization.code})` : ""}
                  </option>
                ))}
              </select>
            </label>

            <label className="mt-4 block text-sm font-semibold">
              مستوى السرية الافتراضي (اختياري)
              <input
                value={confidentialityLevelId}
                onChange={(event) =>
                  setConfidentialityLevelId(event.target.value)
                }
                placeholder="UUID"
                className="mt-2 w-full rounded-lg border border-[#c1c8c6] px-3 py-2 font-mono text-xs"
              />
            </label>

            <button
              type="button"
              onClick={() => importMutation.mutate()}
              disabled={
                !selectedPackage ||
                selectedPackage.status !== "VERIFIED" ||
                !targetOrganizationId ||
                importMutation.isPending
              }
              className="mt-4 w-full rounded-lg bg-[#002623] px-4 py-2.5 text-sm font-semibold text-white disabled:opacity-50"
            >
              {importMutation.isPending
                ? "جارٍ بدء الاستيراد…"
                : "ابدأ الاستيراد"}
            </button>

            {selectedPackage &&
              selectedPackage.status !== "VERIFIED" && (
                <p className="mt-2 text-xs text-amber-800">
                  يجب أن تكون الحزمة VERIFIED قبل بدء الاستيراد.
                </p>
              )}

            {importMutation.isError && (
              <p className="mt-3 rounded-lg bg-red-50 p-3 text-sm text-red-800">
                رفض الخادم بدء الاستيراد. تحقق من حالة الحزمة وربط المؤسسة
                ومستوى السرية.
              </p>
            )}
          </article>
        </aside>
      </div>

      {selectedPackage && (
        <article className="rounded-xl bg-white p-6 shadow-sm">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <h2 className="font-bold">5. حالة عملية الاستيراد</h2>
              <p className="mt-1 text-xs text-[#717977]">
                يتم التحديث تلقائيًا أثناء التنفيذ.
              </p>
            </div>
            <button
              type="button"
              onClick={() => operationsQuery.refetch()}
              className="rounded-md border border-[#c1c8c6] px-3 py-2 text-xs"
            >
              تحديث الآن
            </button>
          </div>

          {operationSummary ? (
            <div className="mt-5 grid gap-4 md:grid-cols-2 xl:grid-cols-5">
              <div className="rounded-lg bg-[#f6f4e9] p-4">
                <p className="text-xs text-[#717977]">حالة الحزمة</p>
                <p className="mt-2 font-bold">
                  {operationSummary.status ?? selectedPackage.status}
                </p>
              </div>
              <div className="rounded-lg bg-[#f6f4e9] p-4">
                <p className="text-xs text-[#717977]">إجمالي المشاريع</p>
                <p className="mt-2 text-2xl font-bold">
                  {operationSummary.projectStats?.total ??
                    operationSummary.manifestProjectsCount ??
                    "—"}
                </p>
              </div>
              <div className="rounded-lg bg-emerald-50 p-4">
                <p className="text-xs text-emerald-700">مستوردة</p>
                <p className="mt-2 text-2xl font-bold text-emerald-900">
                  {operationSummary.projectStats?.imported ?? 0}
                </p>
              </div>
              <div className="rounded-lg bg-amber-50 p-4">
                <p className="text-xs text-amber-700">متخطاة/مكررة</p>
                <p className="mt-2 text-2xl font-bold text-amber-900">
                  {(operationSummary.projectStats?.skipped ?? 0) +
                    (operationSummary.projectStats?.duplicate ?? 0)}
                </p>
              </div>
              <div className="rounded-lg bg-red-50 p-4">
                <p className="text-xs text-red-700">فاشلة</p>
                <p className="mt-2 text-2xl font-bold text-red-900">
                  {operationSummary.projectStats?.failed ?? 0}
                </p>
              </div>
            </div>
          ) : (
            <p className="mt-5 text-sm text-[#717977]">
              لا توجد بيانات عمليات متاحة بعد.
            </p>
          )}

          {operationSummary?.errorMessage && (
            <div className="mt-4 rounded-lg bg-red-50 p-4 text-sm text-red-800">
              {operationSummary.errorMessage}
            </div>
          )}
        </article>
      )}
    </section>
  );
}
