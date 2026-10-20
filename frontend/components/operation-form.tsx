"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowRight, CirclePlus, Send, Trash2 } from "lucide-react";
import { apiRequest } from "@/lib/api/client";
import { endpointByKey } from "@/lib/api/catalog";
import type { OperationDefinition, OperationField } from "@/lib/api/operation-definitions";
import { ReferenceSelect } from "@/components/reference-select";
import { appendJsonPart, memberPayload, type MemberInput } from "@/lib/api/payloads";
import { DataView } from "@/components/data-view";
import type { ReferenceSource } from "@/lib/api/reference-options";
import { hasAnyRoleResponse } from "@/lib/api/permissions";

type DataRecord = Record<string, unknown>;
const memberFieldNames: Record<string, string> = { personName: "person_name", organizationName: "organization_name", roleName: "role_name" };

function isRecord(value: unknown): value is DataRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function unwrap(value: unknown): unknown {
  return isRecord(value) && "data" in value ? value.data : value;
}

function listFrom(value: unknown): DataRecord[] {
  const data = unwrap(value);
  if (Array.isArray(data)) return data.filter(isRecord);
  if (!isRecord(data)) return [];
  for (const key of ["items", "content", "records", "results", "rows", "list"]) {
    if (Array.isArray(data[key])) return data[key].filter(isRecord);
  }
  return [];
}

function recordFrom(value: unknown): DataRecord | null {
  const data = unwrap(value);
  return isRecord(data) ? data : null;
}

type OrganizationMapping = { sourceId: string; targetId: string };

function OrganizationMappingField({ value, onChange, className }: { value: OrganizationMapping[]; onChange: (value: OrganizationMapping[]) => void; className: string }) {
  const source: ReferenceSource = { endpoint: "organizations.all", labelStyle: "organization" };
  return <div className="sm:col-span-2">
    <div className="mb-2 flex items-center justify-between gap-3">
      <span className="text-sm font-medium text-[#414847]">ربط مؤسسات الحزمة (اختياري)</span>
      <button type="button" onClick={() => onChange([...value, { sourceId: "", targetId: "" }])} className="rounded-lg border border-[#c1c8c6] px-3 py-2 text-xs text-[#002623]">إضافة ربط</button>
    </div>
    {value.map((mapping, index) => <div key={index} className="mb-2 grid gap-3 rounded-lg border border-[#e5e3d8] p-3 sm:grid-cols-2">
      <label className="text-xs text-[#414847]">المؤسسة المصدر في الحزمة<ReferenceSelect source={source} value={mapping.sourceId} onChange={(sourceId) => onChange(value.map((item, row) => row === index ? { ...item, sourceId } : item))} label="المؤسسة المصدر" className={className} /></label>
      <label className="text-xs text-[#414847]">المؤسسة المقابلة في النظام<ReferenceSelect source={source} value={mapping.targetId} onChange={(targetId) => onChange(value.map((item, row) => row === index ? { ...item, targetId } : item))} label="المؤسسة" className={className} /></label>
      <button type="button" onClick={() => onChange(value.filter((_, row) => row !== index))} className="text-right text-xs text-[#93000a]">إزالة الربط</button>
    </div>)}
    <p className="mt-1 text-xs text-[#717977]">اختر المؤسسة المصدر في الحزمة والمؤسسة المقابلة لها في النظام؛ تُرسل المعرّفات تلقائيًا.</p>
  </div>;
}

function getEndpointFor(operation: OperationDefinition) {
  if (operation.key === "projectMembers.update") return "projectMembers.list";
  if (operation.method !== "PATCH" || operation.pathParams.length !== 1) return null;
  const group = operation.group === "migrationBatches" ? "migrationBatches" : operation.group;
  const key = `${group}.get`;
  return endpointByKey.has(key) ? key : null;
}

export function OperationForm({ operation, initialParams = {} }: { operation: OperationDefinition; initialParams?: Record<string, string> }) {
  const queryClient = useQueryClient();
  const [values, setValues] = useState<Record<string, string>>({});
  const [files, setFiles] = useState<Record<string, File[]>>({});
  const [members, setMembers] = useState<MemberInput[]>([]);
  const [organizationMappings, setOrganizationMappings] = useState<OrganizationMapping[]>([]);
  const [error, setError] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  const detailEndpoint = getEndpointFor(operation);
  const detail = useQuery({
    queryKey: [detailEndpoint, initialParams.id],
    queryFn: () => apiRequest<unknown>(detailEndpoint!, "GET", {}, undefined, { id: initialParams.id }),
    enabled: Boolean(detailEndpoint && initialParams.id),
  });
  const packageReceiver = useQuery({
    queryKey: ["auth.me"],
    queryFn: () => apiRequest<unknown>("auth.me", "GET"),
    enabled: operation.key === "packages.receive",
  });
  const canReceivePackage = packageReceiver.data ? hasAnyRoleResponse(packageReceiver.data, ["system_admin", "import_officer"]) : null;

  const detailData = operation.key === "projectMembers.update"
    ? listFrom(detail.data).find((item) => String(item.id) === initialParams.memberId)
    : unwrap(detail.data);
  const prefillValues: Record<string, string> = {};
  if (isRecord(detailData)) {
    for (const field of operation.fields) {
      if (field.kind === "password") continue;
      const value = detailData[field.name] ?? detailData[memberFieldNames[field.name]];
      if (value === null || value === undefined) continue;
      prefillValues[field.name] = typeof value === "object" ? JSON.stringify(value, null, 2) : String(value);
    }
  }

  const mutation = useMutation({
    mutationFn: async ({ query, body, params }: { query: Record<string, string>; body?: unknown; params: Record<string, string> }) =>
      apiRequest<unknown>(operation.key, operation.method, query, body, params),
    onSuccess: async () => {
      if (operation.key === "packages.receive") {
        setValues({});
        setFiles({});
      }
      await queryClient.invalidateQueries({ queryKey: [operation.group] });
      await queryClient.invalidateQueries({ queryKey: [`${operation.group}.list`] });
    },
  });

  const update = (name: string, value: string) => setValues((current) => ({
    ...current,
    [name]: value,
    ...(name === "categoryId" ? { typeId: "" } : {}),
  }));
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError("");
    mutation.reset();
    if (operation.method === "DELETE" && !confirmed) {
      setError("يرجى تأكيد الحذف قبل المتابعة.");
      return;
    }
    const params: Record<string, string> = {};
    for (const name of operation.pathParams) {
      const value = (values[`path:${name}`] ?? initialParams[name] ?? "").trim();
      if (!value) { setError("أدخل معرّف السجل المطلوب."); return; }
      params[name] = value;
    }
    const query: Record<string, string> = {};
    for (const field of operation.queryFields) {
      const value = (values[`query:${field.name}`] ?? "").trim();
      if (field.required && !value) { setError(`حقل ${field.label} مطلوب.`); return; }
      if (value) query[field.name] = value;
    }
    let body: unknown;
    if (operation.body === "form-data") body = new FormData();
    else if (operation.body === "json") body = {} as DataRecord;
    if (operation.key === "migrationBatches.run" && isRecord(body)) body.migration_config = {};
    const fileMetadata: DataRecord = {};
    const hasFileMetadata = operation.key === "files.upload" || operation.key === "files.uploadBatch";

    if (operation.group === "migrationSources") {
      const connectionNames = ["host", "port", "databaseName", "username", "password"] as const;
      const connection = Object.fromEntries(connectionNames.map((name) => [name, (values[name] ?? prefillValues[name] ?? "").trim()]));
      if (["host", "port", "username", "password"].some((name) => Boolean(connection[name]))) {
        const needed = connectionNames.filter((name) => !connection[name] && !(name === "password" && operation.key === "migrationSources.update" && isRecord(detailData) && detailData.hasStoredConnection === true));
        if (needed.length) { setError("أكمل عنوان الخادم والمنفذ وقاعدة البيانات واسم المستخدم وكلمة المرور عند استخدام اتصال مخصص."); return; }
      }
      if (connection.port && (!Number.isInteger(Number(connection.port)) || Number(connection.port) < 1 || Number(connection.port) > 65535)) { setError("أدخل منفذًا بين 1 و65535."); return; }
    }

    for (const field of operation.fields) {
      if (field.kind === "organization-mapping") {
        if (organizationMappings.some((mapping) => Boolean(mapping.sourceId.trim()) !== Boolean(mapping.targetId))) {
          setError("أكمل معرّف المؤسسة المصدر والمؤسسة الهدف لكل ربط.");
          return;
        }
        const mappings = organizationMappings.filter((mapping) => mapping.sourceId.trim());
        if (new Set(mappings.map((mapping) => mapping.sourceId.trim())).size !== mappings.length) {
          setError("لا يمكن تكرار معرّف المؤسسة المصدر في الربط.");
          return;
        }
        if (isRecord(body)) body.organization_mapping = Object.fromEntries(mappings.map((mapping) => [mapping.sourceId.trim(), mapping.targetId]));
        continue;
      }
      if (field.kind === "members") {
        const invalid = members.some((member) => !member.personName.trim() || !member.organizationName.trim() || !member.roleName.trim());
        if (invalid) { setError("أكمل اسم الشخص والجهة والدور لكل عضو."); return; }
        if (isRecord(body)) body.members = members.map(memberPayload);
        continue;
      }
      const raw = (values[field.name] ?? prefillValues[field.name] ?? "").trim();
      const selectedFiles = files[field.name] ?? [];
      if (field.required && !raw && selectedFiles.length === 0) { setError(`حقل ${field.label} مطلوب.`); return; }
      if (field.kind === "file" || field.kind === "files") {
        if (operation.key === "packages.receive" && field.name === "package_file" && selectedFiles.some((file) => !/\.(pkg|zip|nxpkg)$/i.test(file.name))) {
          setError("امتداد ملف الحزمة يجب أن يكون .pkg أو .zip أو .nxpkg.");
          return;
        }
        if (body instanceof FormData) selectedFiles.forEach((file) => body.append(field.name, file));
        continue;
      }
      if (!raw) continue;
      let value: unknown = raw;
      if (field.kind === "number") {
        value = Number(raw);
        if (!Number.isFinite(value)) { setError(`قيمة ${field.label} غير صالحة.`); return; }
      } else if (field.kind === "boolean") value = raw === "true";
      else if (field.kind === "ids" || field.multiple) value = raw.split(",").map((item) => item.trim()).filter(Boolean);
      else if (field.kind === "json") {
        try { value = JSON.parse(raw); }
        catch { setError(`صيغة JSON في ${field.label} غير صالحة.`); return; }
        if (!isRecord(value)) { setError(`يجب أن تكون ${field.label} كائناً بصيغة JSON.`); return; }
      }
      if (body instanceof FormData) {
        if (hasFileMetadata && ["categoryId", "typeId", "confidentialityLevelId"].includes(field.name)) fileMetadata[field.name] = value;
        else if (field.kind === "json") appendJsonPart(body, field.name, value);
        else body.append(field.name, String(value));
      } else if (isRecord(body)) body[memberFieldNames[field.name] && operation.group === "projectMembers" ? memberFieldNames[field.name] : field.name] = value;
    }
    if (body instanceof FormData && hasFileMetadata) appendJsonPart(body, "metadata", {
      ...fileMetadata,
      confidentialityLevelId: fileMetadata.confidentialityLevelId ?? null,
    });
    mutation.mutate({ query, body, params });
  };

  const pathLookups: Record<string, OperationField> = operation.key === "migrationBatches.create"
    ? { sourceId: { name: "sourceId", label: "مصدر الترحيل", required: true, lookup: "migrationSources.list" } }
    : {};
  const fields: OperationField[] = [
    ...operation.pathParams.map((name) => ({ ...pathLookups[name], name: `path:${name}`, label: pathLookups[name]?.label ?? (name === "id" ? "معرّف السجل" : name === "memberId" ? "معرّف العضو" : name === "roleId" ? "معرّف الدور" : name), required: true })),
    ...operation.queryFields.map((field) => ({ ...field, name: `query:${field.name}` })),
    ...operation.fields,
  ];

  if (operation.key === "packages.receive" && packageReceiver.isLoading) return <p className="rounded-xl bg-white p-6 text-sm text-[#717977]">جارٍ التحقق من صلاحية استقبال الحزم…</p>;
  if (operation.key === "packages.receive" && packageReceiver.isError) return <p role="alert" className="rounded-xl bg-[#ffdad6] p-6 text-sm text-[#93000a]">تعذر التحقق من صلاحية استقبال الحزم: {packageReceiver.error.message}</p>;
  if (operation.key === "packages.receive" && canReceivePackage !== true) return <p role="alert" className="rounded-xl bg-[#ffdad6] p-6 text-sm text-[#93000a]">يتطلب استقبال الحزم دور system_admin أو import_officer.</p>;

  return <section className="mx-auto max-w-4xl space-y-5">
    <header className="rounded-xl bg-white p-6 shadow-sm">
      <Link href="/operations" className="inline-flex items-center gap-2 text-sm text-[#33675d]"><ArrowRight size={16} aria-hidden="true" />كل العمليات</Link>
      <h1 className="mt-3 text-2xl font-bold text-[#1b1c15]">{operation.title}</h1>
    </header>
    <form onSubmit={submit} className="space-y-5 rounded-xl bg-white p-6 shadow-sm">
      {fields.length === 0 && <p className="text-sm text-[#414847]">اضغط الزر أدناه للمتابعة.</p>}
      <div className="grid gap-4 sm:grid-cols-2">{fields.map((field) => {
        const value = values[field.name] ?? prefillValues[field.name] ?? (field.name.startsWith("path:") ? initialParams[field.name.slice(5)] ?? "" : "");
        const inputClass = "mt-1.5 w-full rounded-lg border border-[#c1c8c6] bg-white px-3 py-2.5 text-sm outline-none focus:border-[#33675d] focus:ring-1 focus:ring-[#33675d]";
        if (field.kind === "members") return <div key={field.name} className="sm:col-span-2"><div className="flex items-center justify-between gap-3"><h2 className="text-sm font-semibold text-[#414847]">{field.label}</h2><button type="button" onClick={() => setMembers((current) => [...current, { personName: "", organizationName: "", roleName: "", notes: "" }])} className="inline-flex items-center gap-1 rounded-lg border border-[#c1c8c6] px-3 py-2 text-xs text-[#002623]"><CirclePlus size={16} aria-hidden="true" />إضافة عضو</button></div><div className="mt-3 space-y-3">{members.map((member, index) => <div key={index} className="grid gap-2 rounded-lg border border-[#e5e3d8] bg-[#fcfaee] p-3 sm:grid-cols-2">{(["personName", "organizationName", "roleName", "notes"] as const).map((name) => <label key={name} className="text-xs text-[#414847]">{{ personName: "اسم الشخص", organizationName: "الجهة", roleName: "الدور", notes: "ملاحظات" }[name]}<input value={member[name]} onChange={(event) => setMembers((current) => current.map((item, memberIndex) => memberIndex === index ? { ...item, [name]: event.target.value } : item))} className="mt-1 w-full rounded-md border border-[#c1c8c6] bg-white px-3 py-2 text-sm" /></label>)}<button type="button" onClick={() => setMembers((current) => current.filter((_, memberIndex) => memberIndex !== index))} className="inline-flex items-center gap-1 text-xs text-[#93000a]"><Trash2 size={14} aria-hidden="true" />إزالة العضو</button></div>)}</div></div>;
        if (field.kind === "organization-mapping") return <OrganizationMappingField key={field.name} value={organizationMappings} onChange={setOrganizationMappings} className={inputClass} />;
        return <label key={field.name} className={`block text-sm font-medium text-[#414847] ${field.kind === "textarea" || field.kind === "json" ? "sm:col-span-2" : ""}`}>
          {field.label}{field.required && <span className="mr-1 text-[#ba1a1a]">*</span>}
          {field.lookup || field.enumKey ? <ReferenceSelect source={{ endpoint: field.lookup, enumKey: field.enumKey, query: field.lookupQuery, pagination: field.pagination, dependsOn: field.dependsOn, valueField: field.valueField, labelStyle: field.labelStyle }} value={field.multiple ? String(value).split(",").filter(Boolean) : value} onChange={(next) => update(field.name, next)} label={field.label} className={`${inputClass} ${field.multiple ? "min-h-32" : ""}`} required={field.required} multiple={field.multiple} disabled={Boolean(field.dependsOn && !(values[field.dependsOn.field] ?? prefillValues[field.dependsOn.field]))} dependsOnValue={field.dependsOn ? values[field.dependsOn.field] ?? prefillValues[field.dependsOn.field] ?? "" : ""} />
              : field.kind === "textarea" || field.kind === "json" ? <textarea className={inputClass} rows={field.kind === "json" ? 5 : 3} value={value} onChange={(event) => update(field.name, event.target.value)} required={field.required} placeholder={field.placeholder} dir={field.kind === "json" ? "ltr" : undefined} />
              : field.kind === "boolean" ? <select className={inputClass} value={value} onChange={(event) => update(field.name, event.target.value)}><option value="">دون تغيير</option><option value="true">نعم</option><option value="false">لا</option></select>
                : field.kind === "file" || field.kind === "files" ? <input className={inputClass} type="file" multiple={field.kind === "files"} accept={field.name === "package_file" ? ".pkg,.zip,.nxpkg" : undefined} required={field.required} onChange={(event) => setFiles((current) => ({ ...current, [field.name]: Array.from(event.target.files ?? []) }))} />
                : <input className={inputClass} type={field.kind === "password" ? "password" : field.kind === "email" ? "email" : field.kind === "number" ? "number" : "text"} value={value} onChange={(event) => update(field.name, event.target.value)} required={field.required} autoComplete={field.name === "passphrase" ? "off" : field.kind === "password" ? "new-password" : undefined} placeholder={field.kind === "ids" ? "id-1, id-2" : field.placeholder} />}
          {field.name === "officeName" && operation.key === "migrationSources.update" && <span className="mt-1 block text-xs text-[#717977]">قد يمنع الخادم تغيير المكتب بعد بدء الترحيل.</span>}
          {field.name === "password" && operation.key === "migrationSources.update" && <span className="mt-1 block text-xs text-[#717977]">اتركها فارغة للاحتفاظ بكلمة المرور الحالية.</span>}
        </label>;
      })}</div>
      {operation.method === "DELETE" && <label className="flex items-center gap-2 rounded-lg bg-[#ffdad6] p-3 text-sm text-[#93000a]"><input type="checkbox" checked={confirmed} onChange={(event) => setConfirmed(event.target.checked)} />أؤكد حذف هذا السجل</label>}
      {error && <p role="alert" className="rounded-lg bg-[#ffdad6] p-3 text-sm text-[#93000a]">{error}</p>}
      {mutation.isError && <p role="alert" className="rounded-lg bg-[#ffdad6] p-3 text-sm text-[#93000a]">{mutation.error.message}</p>}
      <button type="submit" disabled={mutation.isPending} className="inline-flex items-center gap-2 rounded-lg bg-[#002623] px-5 py-2.5 text-sm font-semibold text-white transition hover:bg-[#33675d] disabled:opacity-60"><Send size={16} aria-hidden="true" />{mutation.isPending ? "جارٍ التنفيذ…" : operation.title}</button>
    </form>
    {mutation.isSuccess && <section role="status" className="overflow-hidden rounded-xl bg-white shadow-sm"><p className="p-5 text-sm font-semibold text-[#184f46]">تم تنفيذ الإجراء.</p>{operation.key === "packages.receive" && typeof recordFrom(mutation.data)?.id === "string" && <p className="px-5 pb-4 text-sm"><Link className="font-semibold text-[#33675d] hover:underline" href={`/records/packages/${encodeURIComponent(String(recordFrom(mutation.data)?.id))}`}>فتح الحزمة للتحقق والاستيراد</Link></p>}{mutation.data !== undefined && <DataView data={mutation.data} />}</section>}
  </section>;
}
