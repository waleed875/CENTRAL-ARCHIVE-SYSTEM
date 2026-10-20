"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiRequest } from "@/lib/api/client";
import { ReferenceSelect } from "@/components/reference-select";
import { DataView } from "@/components/data-view";
import type { ReferenceSource } from "@/lib/api/reference-options";

type DataRecord = Record<string, unknown>;
type MappingKey = "organization_mapping" | "project_type_mapping" | "confidentiality_mapping" | "file_category_mapping" | "file_type_mapping";
type SourceReferenceKey = "organizations" | "projectTypes" | "confidentialityLevels" | "fileCategories" | "fileTypes";
type SourceReferences = Record<SourceReferenceKey, string[]>;
type MappingValues = Record<MappingKey, Record<string, string>>;

const referenceDefinitions: { key: MappingKey; field: keyof SourceReferences; title: string; endpoint: string }[] = [
  { key: "organization_mapping", field: "organizations", title: "المؤسسات", endpoint: "organizations.all" },
  { key: "project_type_mapping", field: "projectTypes", title: "أنواع المشاريع", endpoint: "projectTypes.all" },
  { key: "confidentiality_mapping", field: "confidentialityLevels", title: "مستويات السرية", endpoint: "confidentialityLevels.all" },
  { key: "file_category_mapping", field: "fileCategories", title: "فئات الملفات", endpoint: "fileCategories.all" },
  { key: "file_type_mapping", field: "fileTypes", title: "أنواع الملفات", endpoint: "fileTypes.all" },
];
const terminalStatuses = new Set(["SUCCESS", "FAILED", "PARTIAL", "INTERRUPTED"]);

function isRecord(value: unknown): value is DataRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function unwrap(value: unknown): unknown {
  return isRecord(value) && "data" in value ? value.data : value;
}

function parseReferences(value: unknown): SourceReferences {
  const data = unwrap(value);
  if (!isRecord(data)) throw new Error("استجابة مراجع الحزمة غير صالحة.");
  const result = {} as SourceReferences;
  for (const { field } of referenceDefinitions) {
    const list = data[field];
    if (!Array.isArray(list) || !list.every((item) => typeof item === "string" && item.length > 0)) {
      throw new Error(`لم تصل قائمة مراجع المصدر المطلوبة (${field}) من الخادم.`);
    }
    result[field] = list;
  }
  return result;
}

function importIdFrom(value: unknown): string {
  const data = unwrap(value);
  if (!isRecord(data) || typeof data.import_id !== "string" || !data.import_id) {
    throw new Error("بدأ الخادم الطلب دون إرجاع معرّف عملية الاستيراد.");
  }
  return data.import_id;
}

function operationStatus(value: unknown, importId: string): string | undefined {
  const data = unwrap(value);
  if (!isRecord(data) || !Array.isArray(data.importOperations)) return undefined;
  const operation = data.importOperations.find((item) => isRecord(item) && String(item.id ?? item.importId ?? "") === importId);
  return isRecord(operation) && typeof operation.status === "string" ? operation.status.toUpperCase() : undefined;
}

function PackageReferenceMap({ title, sourceIds, endpoint, values, onChange }: {
  title: string;
  sourceIds: string[];
  endpoint: string;
  values: Record<string, string>;
  onChange: (sourceId: string, targetId: string) => void;
}) {
  const source: ReferenceSource = { endpoint, labelStyle: endpoint === "organizations.all" ? "organization" : undefined };
  return <fieldset className="rounded-lg border border-[#e5e3d8] p-4">
    <legend className="px-2 text-sm font-semibold">{title}</legend>
    {sourceIds.length === 0 ? <p className="text-sm text-[#717977]">لا توجد مراجع من هذا النوع في الحزمة.</p> : <div className="space-y-3">
      {sourceIds.map((sourceId) => <label key={sourceId} className="grid gap-2 text-xs text-[#414847] sm:grid-cols-2 sm:items-center">
        <span dir="ltr" className="break-all font-mono">{sourceId}</span>
        <ReferenceSelect source={source} value={values[sourceId] ?? ""} onChange={(value) => onChange(sourceId, value)} label={`القيمة المركزية لـ ${title}`} className="w-full rounded-lg border border-[#c1c8c6] bg-white px-3 py-2.5 text-sm" />
      </label>)}
    </div>}
  </fieldset>;
}

export function PackageImportWorkflow({ packageId, initiallyVerified }: { packageId: string; initiallyVerified: boolean }) {
  const queryClient = useQueryClient();
  const [verified, setVerified] = useState(initiallyVerified);
  const [mappings, setMappings] = useState<MappingValues>({
    organization_mapping: {},
    project_type_mapping: {},
    confidentiality_mapping: {},
    file_category_mapping: {},
    file_type_mapping: {},
  });
  const [defaultConfidentialityId, setDefaultConfidentialityId] = useState("");
  const [importId, setImportId] = useState("");
  const referenceQuery = useQuery({
    queryKey: ["packages.references", packageId],
    queryFn: async () => parseReferences(await apiRequest<unknown>("packages.references", "GET", {}, undefined, { id: packageId })),
    enabled: verified,
  });
  const verifyMutation = useMutation({
    mutationFn: async () => {
      const response = await apiRequest<unknown>("packages.verify", "POST", {}, undefined, { id: packageId });
      const data = unwrap(response);
      if (!isRecord(data) || typeof data.valid !== "boolean") throw new Error("تعذر قراءة نتيجة التحقق من الخادم.");
      if (!data.valid) throw new Error(typeof data.message === "string" && data.message ? data.message : "فشل التحقق من الحزمة.");
      return response;
    },
    onSuccess: async () => {
      setVerified(true);
      await queryClient.invalidateQueries({ queryKey: ["packages.references", packageId] });
      await queryClient.invalidateQueries({ queryKey: ["packages.get", packageId] });
    },
  });
  const importMutation = useMutation({
    mutationFn: async () => {
      if (!referenceQuery.data) throw new Error("تعذر تحميل مراجع الحزمة.");
      const references = referenceQuery.data;
      for (const { key, field } of referenceDefinitions) {
        if (references[field].some((sourceId) => !mappings[key][sourceId])) {
          throw new Error("أكمل ربط كل مراجع المصدر بالقيم المركزية قبل الاستيراد.");
        }
      }
      const body = {
        organization_mapping: mappings.organization_mapping,
        project_type_mapping: mappings.project_type_mapping,
        confidentiality_mapping: mappings.confidentiality_mapping,
        file_category_mapping: mappings.file_category_mapping,
        file_type_mapping: mappings.file_type_mapping,
        confidentiality_level_id: defaultConfidentialityId || null,
      };
      const response = await apiRequest<unknown>("packages.import", "POST", {}, body, { id: packageId });
      return importIdFrom(response);
    },
    onSuccess: async (id) => {
      setImportId(id);
      await queryClient.invalidateQueries({ queryKey: ["packages.operations", packageId] });
    },
  });
  const operationsQuery = useQuery({
    queryKey: ["packages.operations", packageId],
    queryFn: () => apiRequest<unknown>("packages.operations", "GET", {}, undefined, { id: packageId }),
    enabled: Boolean(importId),
    refetchInterval: (query) => importId && terminalStatuses.has(operationStatus(query.state.data, importId) ?? "") ? false : 3000,
  });
  const refs = referenceQuery.data;
  const status = operationsQuery.data === undefined ? undefined : operationStatus(operationsQuery.data, importId);
  const statusLabel: Record<string, string> = {
    STARTED: "قيد التنفيذ",
    SUCCESS: "اكتمل الاستيراد",
    FAILED: "فشل الاستيراد",
    PARTIAL: "اكتمل الاستيراد جزئيًا",
    INTERRUPTED: "توقفت العملية",
  };

  return <section className="space-y-5 rounded-xl bg-white p-5 shadow-sm">
    <div>
      <h2 className="font-bold">التحقق والاستيراد</h2>
      <p className="mt-1 text-sm text-[#717977]">لا يبدأ الاستيراد إلا بعد نجاح التحقق. طابق كل معرّف مصدر مع السجل المركزي المقابل.</p>
    </div>
    {!verified && <button type="button" disabled={verifyMutation.isPending} onClick={() => verifyMutation.mutate()} className="rounded-lg bg-[#002623] px-4 py-2.5 text-sm font-semibold text-white disabled:opacity-50">{verifyMutation.isPending ? "جارٍ التحقق…" : "التحقق من الحزمة"}</button>}
    {verifyMutation.isError && <p role="alert" className="text-sm text-[#93000a]">{verifyMutation.error.message}</p>}
    {verified && <>
      {referenceQuery.isLoading && <p className="text-sm text-[#717977]">جارٍ تحميل مراجع الحزمة…</p>}
      {referenceQuery.isError && <p role="alert" className="text-sm text-[#93000a]">تعذر تحميل مراجع الحزمة: {referenceQuery.error.message}</p>}
      {refs && <div className="space-y-4">
        {referenceDefinitions.map(({ key, field, title, endpoint }) => <PackageReferenceMap key={key} title={title} sourceIds={refs[field]} endpoint={endpoint} values={mappings[key]} onChange={(sourceId, targetId) => setMappings((current) => ({ ...current, [key]: { ...current[key], [sourceId]: targetId } }))} />)}
        <label className="block text-sm font-medium text-[#414847]">مستوى السرية الافتراضي عند غياب السرية في المصدر <ReferenceSelect source={{ endpoint: "confidentialityLevels.all" }} value={defaultConfidentialityId} onChange={setDefaultConfidentialityId} label="مستوى السرية الافتراضي" className="mt-1.5 w-full rounded-lg border border-[#c1c8c6] bg-white px-3 py-2.5 text-sm" /></label>
        {!importId && <button type="button" disabled={importMutation.isPending || referenceQuery.isError} onClick={() => importMutation.mutate()} className="rounded-lg bg-[#002623] px-4 py-2.5 text-sm font-semibold text-white disabled:opacity-50">{importMutation.isPending ? "جارٍ بدء الاستيراد…" : "بدء الاستيراد"}</button>}
      </div>}
      {importMutation.isError && <p role="alert" className="text-sm text-[#93000a]">{importMutation.error.message}</p>}
    </>}
    {importId && <div className="space-y-2 rounded-lg bg-[#f6f4e9] p-4">
      <p className="text-sm font-semibold">{status ? statusLabel[status] ?? `حالة العملية: ${status}` : "تم إرسال طلب الاستيراد؛ جارٍ انتظار حالة العملية."}</p>
      <p className="text-xs text-[#717977]" dir="ltr">import_id: {importId}</p>
      {operationsQuery.isError && <p role="alert" className="text-sm text-[#93000a]">تعذر متابعة حالة عملية الاستيراد: {operationsQuery.error.message}</p>}
      {operationsQuery.data !== undefined && <DataView data={operationsQuery.data} />}
    </div>}
  </section>;
}
