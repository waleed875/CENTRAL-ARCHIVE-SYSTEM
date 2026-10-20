import { BadgeCheck, ClipboardClock, ContactRound, FileInput, FileText, FolderKanban, Landmark, Package, type LucideIcon } from "lucide-react";

type DataRecord = Record<string, unknown>;

type CardDefinition = {
  key: string;
  title: string;
  description: string;
  badge: string;
  icon: LucideIcon;
  iconColor: string;
  aliases: string[];
  detailKeys?: string[];
};

const cards: CardDefinition[] = [
  { key: "projects", title: "إجمالي المشاريع", description: "المشاريع المسجلة", badge: "مشروع", icon: FolderKanban, iconColor: "text-[#33675d]", aliases: ["projects"] },
  { key: "files", title: "إجمالي الملفات", description: "الملفات المسجلة", badge: "ملف", icon: FileText, iconColor: "text-[#6b5d35]", aliases: ["files"] },
  { key: "organizations", title: "إجمالي المنظمات", description: "المنظمات المسجلة", badge: "منظمة", icon: Landmark, iconColor: "text-[#000c0b]", aliases: ["organizations"] },
  { key: "users", title: "المستخدمون المصرحون", description: "حسابات المستخدمين", badge: "مستخدم", icon: ContactRound, iconColor: "text-[#33675d]", aliases: ["users"] },
  { key: "packages", title: "الحزم الواردة", description: "الحزم المستلمة", badge: "حزمة", icon: Package, iconColor: "text-[#6b5d35]", aliases: ["packages", "incomingPackages"] },
  { key: "imports", title: "عمليات الاستيراد", description: "عمليات الاستيراد المسجلة", badge: "عملية", icon: FileInput, iconColor: "text-[#33675d]", aliases: ["importOperations", "imports", "importJobs", "importProcesses"] },
  { key: "pendingReviews", title: "قيد المراجعة", description: "المشاريع قيد المراجعة", badge: "معلق", icon: ClipboardClock, iconColor: "text-[#6b5d35]", aliases: ["pendingReviews", "projectsPendingReview"], detailKeys: ["pendingReviews", "pendingReview", "underReview"] },
  { key: "archived", title: "المؤرشفة نهائياً", description: "المشاريع المؤرشفة", badge: "مؤرشف", icon: BadgeCheck, iconColor: "text-[#f5e1af]", aliases: ["archivedProjects", "finalizedProjects", "archived"], detailKeys: ["archived", "archivedProjects", "completed", "finalized"] },
];

const numberFormatter = new Intl.NumberFormat("ar-SA", { maximumFractionDigits: 2 });
const badgeClasses = [
  "bg-[#b4ebdd] text-[#386c61]",
  "bg-[#eae8dd] text-[#414847]",
  "text-[#33675d] font-semibold",
  "text-[#414847]",
  "bg-[#eae8dd] text-[#1b1c15]",
  "text-[#6b5d35] font-semibold",
  "bg-[#6b5d35]/15 text-[#6b5d35] font-semibold",
  "bg-[#33675d] text-white",
];

function isRecord(value: unknown): value is DataRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function numberFrom(value: unknown): number | null {
  if (typeof value === "number") return Number.isFinite(value) && value >= 0 ? value : null;
  if (typeof value === "string" && /^\d+(\.\d+)?$/.test(value.trim())) return Number(value);
  return null;
}

function countFrom(value: unknown): number | null {
  const direct = numberFrom(value);
  if (direct !== null) return direct;
  if (!isRecord(value)) return null;
  for (const key of ["total", "totalCount", "count", "value"]) {
    const count = numberFrom(value[key]);
    if (count !== null) return count;
  }
  const totalEntry = Object.entries(value).find(([key, item]) => /^total[A-Z_]/.test(key) && numberFrom(item) !== null);
  return totalEntry ? numberFrom(totalEntry[1]) : null;
}

function findValue(payload: DataRecord, definition: CardDefinition): number | null {
  for (const alias of definition.aliases) {
    const value = countFrom(payload[alias]);
    if (value !== null) return value;
  }
  if (definition.detailKeys && isRecord(payload.projects)) {
    for (const key of definition.detailKeys) {
      const value = countFrom(payload.projects[key]);
      if (value !== null) return value;
    }
  }
  return null;
}

function supportingDetail(value: unknown): { label: string; value: number } | null {
  if (!isRecord(value)) return null;
  const labels: Record<string, string> = { active: "نشط", pending: "قيد المعالجة", completed: "مكتمل", archived: "مؤرشف" };
  for (const key of ["active", "pending", "completed", "archived"]) {
    const count = numberFrom(value[key]);
    if (count !== null) return { label: labels[key], value: count };
  }
  return null;
}

export function DashboardStatsCards({ data }: { data: unknown }) {
  const payload = isRecord(data) && isRecord(data.data) ? data.data : data;
  const stats = isRecord(payload) ? payload : {};

  return <section aria-label="مؤشرات الأداء الرئيسية" className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
    {cards.map((card, index) => {
      const Icon = card.icon;
      const value = findValue(stats, card);
      const detail = supportingDetail(stats[card.aliases[0]]);
      const featured = index === cards.length - 1;
      return <article key={card.key} className={`relative flex min-h-32 flex-col justify-between overflow-hidden rounded-lg p-4 shadow-sm ${featured ? "bg-[#002623] text-white" : "bg-white text-[#1b1c15]"}`}>
        {(index < 2 || featured) && <span aria-hidden="true" className={`pointer-events-none absolute h-24 w-24 rounded-full ${featured ? "-bottom-8 -right-8 bg-[#33675d]/30" : "-left-6 -top-6 bg-[#33675d]/10"}`} />}
        <div className="relative mb-3 flex items-center justify-between gap-2">
          <h2 className={`text-[11px] font-semibold leading-4 ${featured ? "text-[#f5e1af]" : index === 6 ? "text-[#6b5d35]" : "text-[#414847]"}`}>{card.title}</h2>
          <span className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-md ${featured ? "bg-[#000c0b]/60" : index === 6 ? "bg-[#6b5d35]/15" : "bg-[#f6f4e9]"} ${card.iconColor}`}><Icon size={18} strokeWidth={1.8} aria-hidden="true" /></span>
        </div>
        <div className="relative flex items-baseline justify-between gap-1">
          <strong className={`text-[32px] font-bold leading-[44px] tabular-nums ${featured ? "text-white" : index === 6 ? "text-[#6b5d35]" : "text-[#1b1c15]"}`}>{value === null ? "—" : numberFormatter.format(value)}</strong>
          <span className={`max-w-[48%] truncate rounded px-1 py-0.5 text-[11px] leading-4 ${badgeClasses[index]}`}>{detail ? `${detail.label}: ${numberFormatter.format(detail.value)}` : value === null ? "غير متاح" : card.badge}</span>
        </div>
        <p className={`relative mt-1 text-[13px] leading-5 ${featured ? "text-[#a9cec9]" : "text-[#414847]"}`}>{card.description}</p>
      </article>;
    })}
  </section>;
}
