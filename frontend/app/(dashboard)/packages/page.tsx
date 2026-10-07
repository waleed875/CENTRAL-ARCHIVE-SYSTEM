"use client";

import { FormEvent, useMemo, useState, type Dispatch, type SetStateAction } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiClientError, apiRequest } from "@/lib/api/client";

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

type LookupOption = {
  id: string;
  name: string;
  code?: string | null;
  isActive?: boolean | null;
  categoryId?: string | null;
};

type Organization = LookupOption;

type ReferenceRequirements = {
  organizations: string[];
  projectTypes: string[];
  confidentialityLevels: string[];
  fileCategories: string[];
  fileTypes: string[];
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
    case "DECRYPTED":
      return "bg-cyan-100 text-cyan-800";
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
  const [organizationMapping, setOrganizationMapping] = useState<Record<string, string>>({});
  const [projectTypeMapping, setProjectTypeMapping] = useState<Record<string, string>>({});
  const [confidentialityMapping, setConfidentialityMapping] = useState<Record<string, string>>({});
  const [fileCategoryMapping, setFileCategoryMapping] = useState<Record<string, string>>({});
  const [fileTypeMapping, setFileTypeMapping] = useState<Record<string, string>>({});
  const [confidentialityLevelId, setConfidentialityLevelId] = useState("");
  const [uploadFile, setUploadFile] = useState<File | null>(null);
  const [uploadPassphrase, setUploadPassphrase] = useState("");

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

  const projectTypesQuery = useQuery({
    queryKey: ["project-types.all", "package-import"],
    queryFn: () =>
      apiRequest<ApiEnvelope<LookupOption[]>>("projectTypes.all", "GET"),
  });

  const confidentialityQuery = useQuery({
    queryKey: ["confidentiality-levels.all", "package-import"],
    queryFn: () =>
      apiRequest<ApiEnvelope<LookupOption[]>>("confidentialityLevels.all", "GET"),
  });

  const fileCategoriesQuery = useQuery({
    queryKey: ["file-categories.all", "package-import"],
    queryFn: () =>
      apiRequest<ApiEnvelope<LookupOption[]>>(
        "fileCategories.all",
        "GET",
        { isActive: "true" },
      ),
  });

  const fileTypesQuery = useQuery({
    queryKey: ["file-types.all", "package-import"],
    queryFn: () =>
      apiRequest<ApiEnvelope<LookupOption[]>>(
        "fileTypes.all",
        "GET",
        { isActive: "true" },
      ),
  });

  const packages = useMemo(
    () => unwrap(packagesQuery.data)?.data ?? [],
    [packagesQuery.data],
  );
  const organizations = useMemo(
    () => unwrap(organizationsQuery.data)?.data ?? [],
    [organizationsQuery.data],
  );
  const projectTypes = unwrap(projectTypesQuery.data) ?? [];
  const confidentialityLevels = unwrap(confidentialityQuery.data) ?? [];
  const fileCategories = unwrap(fileCategoriesQuery.data) ?? [];
  const fileTypes = unwrap(fileTypesQuery.data) ?? [];

  const selectedPackage = useMemo(
    () => packages.find((item) => item.id === selectedId),
    [packages, selectedId],
  );

  const referencesQuery = useQuery({
    queryKey: ["packages.references", selectedId],
    enabled: Boolean(selectedId),
    queryFn: () =>
      apiRequest<ApiEnvelope<ReferenceRequirements>>(
        "packages.references",
        "GET",
        {},
        undefined,
        { id: selectedId },
      ),
  });

  const references = unwrap(referencesQuery.data) ?? {
    organizations: [],
    projectTypes: [],
    confidentialityLevels: [],
    fileCategories: [],
    fileTypes: [],
  };

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
      const encrypted = /\.(zip|nxpkg)$/i.test(uploadFile.name);
      if (encrypted) {
        if (!uploadPassphrase.trim()) {
          throw new Error("PASSPHRASE_REQUIRED");
        }
        form.append("passphrase", uploadPassphrase);
      }
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
      setUploadPassphrase("");
      if (created?.id) setSelectedId(created.id);
      await queryClient.invalidateQueries({ queryKey: ["packages.list"] });
    },
  });

  const importMutation = useMutation({
    mutationFn: async () => {
      if (!selectedPackage) throw new Error("PACKAGE_REQUIRED");

      return apiRequest<ApiEnvelope<Record<string, string>>>(
        "packages.import",
        "POST",
        {},
        {
          organization_mapping: organizationMapping,
          project_type_mapping: projectTypeMapping,
          confidentiality_mapping: confidentialityMapping,
          file_category_mapping: fileCategoryMapping,
          file_type_mapping: fileTypeMapping,
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

  function hasCentralMatch(sourceId: string, options: LookupOption[]) {
    return options.some((option) => option.id === sourceId);
  }

  function unresolved(
    sourceIds: string[],
    mapping: Record<string, string>,
    options: LookupOption[],
    allowDefault = false,
  ) {
    return sourceIds.filter(
      (sourceId) =>
        !mapping[sourceId] &&
        !hasCentralMatch(sourceId, options) &&
        !(allowDefault && confidentialityLevelId),
    );
  }

  const unresolvedReferences = [
    ...unresolved(references.organizations, organizationMapping, organizations),
    ...unresolved(references.projectTypes, projectTypeMapping, projectTypes),
    ...unresolved(
      references.confidentialityLevels,
      confidentialityMapping,
      confidentialityLevels,
      true,
    ),
    ...unresolved(references.fileCategories, fileCategoryMapping, fileCategories),
    ...unresolved(references.fileTypes, fileTypeMapping, fileTypes),
  ];

  function updateMapping(
    setter: Dispatch<SetStateAction<Record<string, string>>>,
    sourceId: string,
    value: string,
  ) {
    setter((current) => {
      const next = { ...current };
      if (value) next[sourceId] = value;
      else delete next[sourceId];
      return next;
    });
  }

  function renderMappingRows(
    title: string,
    sourceIds: string[],
    mapping: Record<string, string>,
    setter: Dispatch<SetStateAction<Record<string, string>>>,
    options: LookupOption[],
  ) {
    if (sourceIds.length === 0) return null;

    return (
      <div className="mt-4 rounded-lg border border-[#e1dfd4] p-3">
        <p className="text-sm font-semibold">{title}</p>
        <div className="mt-2 space-y-3">
          {sourceIds.map((sourceId) => {
            const direct = hasCentralMatch(sourceId, options);
            return (
              <label key={sourceId} className="block text-xs text-[#59605e]">
                <span className="font-mono">{sourceId}</span>
                <select
                  value={mapping[sourceId] ?? (direct ? sourceId : "")}
                  onChange={(event) =>
                    updateMapping(setter, sourceId, event.target.value)
                  }
                  className="mt-1 w-full rounded-lg border border-[#c1c8c6] bg-white px-3 py-2 text-sm"
                >
                  <option value="">اختر المقابل المركزي</option>
                  {options.map((option) => (
                    <option key={option.id} value={option.id}>
                      {option.name}
                      {option.code ? ` (${option.code})` : ""}
                    </option>
                  ))}
                </select>
                {direct && !mapping[sourceId] && (
                  <span className="mt-1 block text-emerald-700">
                    موجود بنفس UUID في المركز.
                  </span>
                )}
              </label>
            );
          })}
        </div>
      </div>
    );
  }

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
              سير العمل المركزي: استلام الحزمة، فك التشفير للحزم المشفرة،
              ثم التحقق والاستيراد الصريح ومتابعة النتيجة.
            </p>
          </div>
          <span className="rounded-full bg-[#f5e1af] px-3 py-1 text-xs font-semibold text-[#4b3e1a]">
            RECEIVE → DECRYPT → VERIFY → MAP → IMPORT
          </span>
        </div>
      </header>

      <form
        onSubmit={submitUpload}
        className="rounded-xl bg-white p-6 shadow-sm"
      >
        <h2 className="font-bold">1. استلام حزمة جديدة</h2>
        <p className="mt-1 text-sm text-[#717977]">
          اختر ملف الحزمة بصيغة .pkg أو .zip أو .nxpkg. الحزم المشفرة تُفك
          أثناء الرفع باستخدام كلمة المرور، ولا يتم حفظ كلمة المرور.
        </p>
        <div className="mt-4 flex flex-col gap-3 sm:flex-row sm:items-center">
          <input
            type="file"
            accept=".pkg,.zip,.nxpkg,application/octet-stream,application/zip"
            onChange={(event) =>
              setUploadFile(event.target.files?.[0] ?? null)
            }
            className="block flex-1 rounded-lg border border-[#c1c8c6] bg-[#f6f4e9] px-3 py-2 text-sm"
          />
          <input
            type="password"
            value={uploadPassphrase}
            onChange={(event) => setUploadPassphrase(event.target.value)}
            placeholder="كلمة مرور الحزمة المشفرة"
            autoComplete="new-password"
            disabled={!uploadFile || !/\.(zip|nxpkg)$/i.test(uploadFile.name)}
            className="block min-w-[240px] rounded-lg border border-[#c1c8c6] bg-white px-3 py-2 text-sm disabled:bg-slate-100 disabled:text-slate-400"
          />
          <button
            type="submit"
            disabled={
              !uploadFile ||
              (/\.(zip|nxpkg)$/i.test(uploadFile.name) && !uploadPassphrase.trim()) ||
              uploadMutation.isPending
            }
            className="rounded-lg bg-[#002623] px-5 py-2.5 text-sm font-semibold text-white disabled:opacity-50"
          >
            {uploadMutation.isPending ? "جارٍ الرفع…" : "استلام الحزمة"}
          </button>
        </div>
        {uploadMutation.isError && (
          <div className="mt-3 rounded-lg bg-red-50 p-3 text-sm text-red-800">
            <p className="font-semibold">فشل استلام الحزمة</p>
            <p className="mt-1">
              {uploadMutation.error instanceof ApiClientError
                ? `HTTP ${uploadMutation.error.status}${uploadMutation.error.code ? ` · ${uploadMutation.error.code}` : ""}: ${uploadMutation.error.message}`
                : uploadMutation.error instanceof Error
                  ? uploadMutation.error.message
                  : "تعذر رفع الحزمة."}
            </p>
          </div>
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
                        setOrganizationMapping({});
                        setProjectTypeMapping({});
                        setConfidentialityMapping({});
                        setFileCategoryMapping({});
                        setFileTypeMapping({});
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

            {referencesQuery.isLoading && (
              <p className="mt-4 text-sm text-[#717977]">
                جارٍ تحميل مراجع الحزمة…
              </p>
            )}

            {renderMappingRows(
              "مطابقة المؤسسات",
              references.organizations,
              organizationMapping,
              setOrganizationMapping,
              organizations,
            )}
            {renderMappingRows(
              "مطابقة أنواع المشاريع",
              references.projectTypes,
              projectTypeMapping,
              setProjectTypeMapping,
              projectTypes,
            )}
            {renderMappingRows(
              "مطابقة مستويات السرية",
              references.confidentialityLevels,
              confidentialityMapping,
              setConfidentialityMapping,
              confidentialityLevels,
            )}
            {renderMappingRows(
              "مطابقة تصنيفات الملفات",
              references.fileCategories,
              fileCategoryMapping,
              setFileCategoryMapping,
              fileCategories,
            )}
            {renderMappingRows(
              "مطابقة أنواع الملفات",
              references.fileTypes,
              fileTypeMapping,
              setFileTypeMapping,
              fileTypes,
            )}

            <label className="mt-4 block text-sm font-semibold">
              مستوى السرية الافتراضي (اختياري)
              <select
                value={confidentialityLevelId}
                onChange={(event) =>
                  setConfidentialityLevelId(event.target.value)
                }
                className="mt-2 w-full rounded-lg border border-[#c1c8c6] bg-white px-3 py-2 text-sm"
              >
                <option value="">بدون مستوى افتراضي</option>
                {confidentialityLevels.map((level) => (
                  <option key={level.id} value={level.id}>
                    {level.name}
                    {level.code ? ` (${level.code})` : ""}
                  </option>
                ))}
              </select>
            </label>

            {unresolvedReferences.length > 0 && (
              <div className="mt-3 rounded-lg bg-amber-50 p-3 text-xs text-amber-900">
                تبقى {unresolvedReferences.length} مراجع مصدرية تحتاج Mapping قبل الاستيراد.
              </div>
            )}

            <button
              type="button"
              onClick={() => importMutation.mutate()}
              disabled={
                !selectedPackage ||
                selectedPackage.status !== "VERIFIED" ||
                unresolvedReferences.length > 0 ||
                referencesQuery.isLoading ||
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
              <div className="mt-3 rounded-lg bg-red-50 p-3 text-sm text-red-800">
                <p className="font-semibold">رفض الخادم بدء الاستيراد</p>
                <p className="mt-1">
                  {importMutation.error instanceof ApiClientError
                    ? `HTTP ${importMutation.error.status}${importMutation.error.code ? ` · ${importMutation.error.code}` : ""}: ${importMutation.error.message}`
                    : importMutation.error instanceof Error
                      ? importMutation.error.message
                      : "تعذر بدء الاستيراد."}
                </p>
              </div>
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
