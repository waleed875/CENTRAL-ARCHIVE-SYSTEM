"use client";

import { useQuery } from "@tanstack/react-query";
import { apiRequest } from "@/lib/api/client";
import { readReferenceOptions, referenceTotalPages, type ReferenceSource } from "@/lib/api/reference-options";

type Props = {
  source: ReferenceSource;
  value: string | string[];
  onChange: (value: string) => void;
  label: string;
  required?: boolean;
  multiple?: boolean;
  disabled?: boolean;
  dependsOnValue?: string;
  className: string;
};

export function ReferenceSelect({ source, value, onChange, label, required, multiple, disabled, dependsOnValue, className }: Props) {
  const ready = !source.dependsOn || Boolean(dependsOnValue);
  const options = useQuery({
    queryKey: ["reference-options", source, source.dependsOn ? dependsOnValue : undefined],
    queryFn: async () => {
      const pathParams: Record<string, string> = {};
      const query = { ...source.query };
      if (source.dependsOn && dependsOnValue) {
        if (source.dependsOn.pathParam) pathParams[source.dependsOn.param] = dependsOnValue;
        else query[source.dependsOn.param] = dependsOnValue;
      }
      const fetchPage = (page?: number) => {
        const pageQuery = { ...query };
        if (source.pagination && page !== undefined) {
          pageQuery[source.pagination.pageParam] = String(page);
          pageQuery[source.pagination.sizeParam] = String(source.pagination.pageSize);
        }
        return apiRequest<unknown>(source.endpoint ?? "enums.list", "GET", pageQuery, undefined, pathParams);
      };
      const firstResponse = await fetchPage(source.pagination ? 1 : undefined);
      const result = readReferenceOptions(firstResponse, source);
      if (!source.pagination) return result;
      const totalPages = referenceTotalPages(firstResponse);
      if (totalPages === undefined) {
        if (result.length < source.pagination.pageSize) return result;
        throw new Error("لم يحدد الخادم إجمالي الصفحات؛ تعذر التأكد من اكتمال قائمة الخيارات.");
      }
      for (let page = 2; page <= totalPages; page += 1) {
        const response = await fetchPage(page);
        result.push(...readReferenceOptions(response, source));
      }
      if (new Set(result.map((option) => option.value)).size !== result.length) {
        throw new Error("تتكرر معرّفات الخيارات في صفحات استجابة الخادم.");
      }
      return result;
    },
    enabled: ready,
  });
  const selected = Array.isArray(value) ? value : value ? [value] : [];
  const selectValue = multiple ? selected : selected[0] ?? "";
  const knownValues = new Set(options.data?.map((option) => option.value) ?? []);
  const unavailable = selected.filter((item) => !knownValues.has(item));
  const loading = ready && options.isLoading;

  return <>
    <select
      className={className}
      value={selectValue}
      onChange={(event) => onChange(multiple ? Array.from(event.currentTarget.selectedOptions, (option) => option.value).join(",") : event.currentTarget.value)}
      required={required}
      multiple={multiple}
      disabled={disabled || !ready || loading || options.isError}
      aria-label={label}
    >
      {!multiple && <option value="">{ready ? `اختر ${label}` : `اختر ${source.dependsOn?.field === "categoryId" ? "فئة الملف أولاً" : "القيمة المطلوبة أولاً"}`}</option>}
      {unavailable.map((item) => <option key={item} value={item}>القيمة الحالية: {item}</option>)}
      {(options.data ?? []).map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
    </select>
    {loading && <span className="mt-1 block text-xs text-[#717977]">جارٍ تحميل الخيارات…</span>}
    {ready && options.isError && <span role="alert" className="mt-1 block text-xs text-[#93000a]">تعذر تحميل الخيارات: {options.error.message}</span>}
    {ready && options.isSuccess && options.data.length === 0 && <span className="mt-1 block text-xs text-[#717977]">لا توجد خيارات متاحة.</span>}
  </>;
}
