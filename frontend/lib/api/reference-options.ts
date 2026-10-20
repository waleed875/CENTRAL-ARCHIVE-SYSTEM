export type ReferenceOption = { value: string; label: string };
export type ReferenceSource = {
  endpoint?: string;
  enumKey?: string;
  query?: Record<string, string>;
  pagination?: { pageParam: string; sizeParam: string; pageSize: number };
  dependsOn?: { field: string; param: string; pathParam?: boolean };
  valueField?: string;
  labelStyle?: "organization" | "project" | "code";
};

type DataRecord = Record<string, unknown>;

function isRecord(value: unknown): value is DataRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function unwrap(value: unknown): unknown {
  return isRecord(value) && "data" in value ? value.data : value;
}

export function referenceTotalPages(value: unknown): number | undefined {
  const data = unwrap(value);
  const candidates = [
    isRecord(data) ? data : undefined,
    isRecord(value) ? value.meta : undefined,
    isRecord(data) ? data.meta : undefined,
  ];
  for (const candidate of candidates) {
    if (isRecord(candidate) && typeof candidate.totalPages === "number" && Number.isInteger(candidate.totalPages) && candidate.totalPages >= 0) {
      return candidate.totalPages;
    }
  }
  return undefined;
}

function recordsFrom(value: unknown): DataRecord[] {
  const data = unwrap(value);
  if (Array.isArray(data)) {
    if (!data.every(isRecord)) throw new Error("استجابة الخيارات تحتوي على بيانات غير صالحة.");
    return data;
  }
  if (isRecord(data)) {
    for (const key of ["items", "content", "records", "results", "rows", "list"]) {
      if (Array.isArray(data[key])) {
        if (!(data[key] as unknown[]).every(isRecord)) throw new Error("استجابة الخيارات تحتوي على بيانات غير صالحة.");
        return data[key] as DataRecord[];
      }
    }
  }
  throw new Error("تعذر قراءة قائمة الخيارات من استجابة الخادم.");
}

function labelFor(row: DataRecord, style?: ReferenceSource["labelStyle"], byId?: Map<string, DataRecord>) {
  const name = String(row.name ?? row.fullName ?? row.full_name ?? row.code ?? row.id ?? "");
  if (style === "code" && row.code) return `${name} — ${String(row.code)}`;
  if (style === "project") {
    const number = row.projectNumber ?? row.projectCode;
    return number ? `${name} — ${String(number)}` : name;
  }
  if (style === "organization" && byId) {
    const names = [name];
    let parentId = row.parentId;
    const visited = new Set<string>([String(row.id)]);
    while (parentId !== null && parentId !== undefined && byId.has(String(parentId)) && !visited.has(String(parentId))) {
      const parent = byId.get(String(parentId))!;
      visited.add(String(parentId));
      names.unshift(String(parent.name ?? parent.id ?? ""));
      parentId = parent.parentId;
    }
    return names.join(" ← ");
  }
  return name;
}

export function readReferenceOptions(value: unknown, source: ReferenceSource): ReferenceOption[] {
  if (source.enumKey) {
    const data = unwrap(value);
    if (!isRecord(data) || !Array.isArray(data[source.enumKey]) || !(data[source.enumKey] as unknown[]).every((item) => typeof item === "string")) {
      throw new Error(`لم تتوفر قائمة القيم المطلوبة (${source.enumKey}) من الخادم.`);
    }
    return (data[source.enumKey] as string[]).map((entry) => ({ value: entry, label: entry }));
  }

  const rows = recordsFrom(value);
  const byId = source.labelStyle === "organization"
    ? new Map(rows.map((row) => [String(row.id), row]))
    : undefined;
  return rows.map((row) => {
    const rawValue = row[source.valueField ?? "id"];
    if (rawValue === undefined || rawValue === null || rawValue === "") {
      throw new Error("يوجد خيار من دون معرّف صالح في استجابة الخادم.");
    }
    return { value: String(rawValue), label: labelFor(row, source.labelStyle, byId) };
  });
}
