import { endpoints } from "./catalog";
import type { ReferenceSource } from "./reference-options";

export type QueryDefinition = {
  key: string;
  title: string;
  group: string;
  pathParams: string[];
  queryParams: string[];
  download: boolean;
  optionSources: Record<string, ReferenceSource>;
};

const params: Record<string, string[]> = {
  "organizations.list": ["search", "parentId", "type", "isActive", "page", "size"],
  "roles.list": ["search", "isActive", "page", "limit", "sort", "order"],
  "users.list": ["search", "organizationId", "status", "role", "page", "limit", "sort", "order"],
  "confidentialityLevels.list": ["page", "size"],
  "projectTypes.list": ["isActive", "page", "size"],
  "fileCategories.list": ["search", "isActive", "page", "size"],
  "fileCategories.all": ["isActive"],
  "fileTypes.list": ["categoryId", "search", "isActive", "page", "size"],
  "fileTypes.byCategory": ["isActive"],
  "projects.list": ["organizationId", "year", "status", "page", "size"],
  "projects.search": ["searchTerm", "organizationId", "status", "year", "projectTypeId", "confidentialityLevelId", "sourceType", "projectUid", "createdBy", "approvedBy", "isLatestVersion", "page", "size", "sortBy", "sortDirection"],
  "projects.stats": ["organizationId"],
  "files.list": ["categoryId", "typeId", "status", "search", "page", "size"],
  "packages.list": ["searchTerm", "status", "sourceOrganizationId", "page", "size"],
  "exportPackages.list": ["sourceOrganizationId"],
  "audit.list": ["userId", "action", "entityType", "entityId", "startDate", "endDate", "page", "size"],
  "audit.entityHistory": ["page", "size"],
  "dashboard.activity": ["limit"],
};

const enumSource = (enumKey: string): ReferenceSource => ({ enumKey });
const organizationSource: ReferenceSource = { endpoint: "organizations.all", labelStyle: "organization" };
const confidentialitySource: ReferenceSource = { endpoint: "confidentialityLevels.all" };
const projectTypeSource: ReferenceSource = { endpoint: "projectTypes.all" };
const categorySource: ReferenceSource = { endpoint: "fileCategories.all" };
const roleSource: ReferenceSource = { endpoint: "roles.all", query: { isActive: "true" } };
const userSource: ReferenceSource = { endpoint: "users.list", pagination: { pageParam: "page", sizeParam: "limit", pageSize: 100 } };
const categoryFileTypeSource: ReferenceSource = { endpoint: "fileTypes.byCategory", query: { isActive: "true" }, dependsOn: { field: "categoryId", param: "id", pathParam: true } };

const optionSources: Record<string, Record<string, ReferenceSource>> = {
  "organizations.list": { parentId: organizationSource, type: enumSource("organizationType") },
  "roles.list": { order: enumSource("sortDirections") },
  "users.list": { organizationId: organizationSource, status: enumSource("userStatus"), role: roleSource, order: enumSource("sortDirections") },
  "projects.list": { organizationId: organizationSource, status: enumSource("projectStatus") },
  "projects.search": {
    organizationId: organizationSource,
    status: enumSource("projectStatus"),
    projectTypeId: projectTypeSource,
    confidentialityLevelId: confidentialitySource,
    sourceType: enumSource("projectSourceType"),
    createdBy: userSource,
    approvedBy: userSource,
    sortBy: enumSource("projectSortFields"),
    sortDirection: enumSource("sortDirections"),
  },
  "files.list": { categoryId: categorySource, typeId: categoryFileTypeSource, status: enumSource("fileStatus") },
  "packages.list": { status: enumSource("packageStatus"), sourceOrganizationId: organizationSource },
  "exportPackages.list": { sourceOrganizationId: organizationSource },
  "audit.list": { userId: userSource, action: enumSource("auditAction"), entityType: enumSource("auditEntityType") },
  "audit.entityHistory": { entityType: enumSource("auditEntityType") },
};

const titles: Record<string, string> = {
  "enums.list": "القيم المحددة",
  "auth.me": "الملف الشخصي",
  "organizations.list": "المؤسسات", "organizations.all": "كل المؤسسات", "organizations.tree": "شجرة المؤسسات", "organizations.get": "تفاصيل المؤسسة", "organizations.subtree": "الشجرة الفرعية", "organizations.path": "مسار المؤسسة",
  "roles.list": "الأدوار", "roles.all": "كل الأدوار", "roles.get": "تفاصيل الدور",
  "users.list": "المستخدمون", "users.get": "تفاصيل المستخدم", "users.roles": "أدوار المستخدم",
  "confidentialityLevels.list": "مستويات السرية", "confidentialityLevels.all": "كل مستويات السرية", "confidentialityLevels.get": "تفاصيل مستوى السرية",
  "projectTypes.list": "أنواع المشاريع", "projectTypes.all": "كل أنواع المشاريع", "projectTypes.get": "تفاصيل نوع المشروع",
  "fileCategories.list": "فئات الملفات", "fileCategories.all": "كل فئات الملفات", "fileCategories.get": "تفاصيل فئة الملف",
  "fileTypes.list": "أنواع الملفات", "fileTypes.all": "كل أنواع الملفات", "fileTypes.byCategory": "أنواع الملفات حسب الفئة", "fileTypes.get": "تفاصيل نوع الملف",
  "projects.list": "المشاريع", "projects.get": "تفاصيل المشروع", "projects.search": "بحث متقدم في المشاريع", "projects.stats": "إحصاءات المشاريع", "projects.versions": "نسخ المشروع",
  "projectMembers.list": "أعضاء المشروع",
  "files.list": "ملفات المشروع", "files.get": "تفاصيل الملف", "files.download": "تنزيل الملف",
  "packages.list": "الحزم", "packages.get": "تفاصيل الحزمة", "packages.projects": "مشاريع الحزمة", "packages.operations": "حالة عمليات الحزمة",
  "migrationSources.list": "مصادر الترحيل", "migrationSources.get": "تفاصيل مصدر الترحيل",
  "migrationBatches.list": "دفعات الترحيل", "migrationBatches.get": "تفاصيل دفعة الترحيل",
  "exportPackages.list": "حزم التصدير", "exportPackages.get": "تفاصيل حزمة التصدير", "exportPackages.manifest": "تنزيل بيان التصدير", "exportPackages.download": "تنزيل حزمة التصدير",
  "audit.list": "سجل التدقيق", "audit.get": "تفاصيل سجل التدقيق", "audit.entityHistory": "سجل الكيان",
  "dashboard.stats": "إحصاءات لوحة التحكم", "dashboard.activity": "النشاط الأخير",
};

export const queries: QueryDefinition[] = endpoints
  .filter((endpoint) => endpoint.method === "GET" && !["migrationSettings.get", "projects.incompleteReport", "projects.reviewQueue", "packages.references"].includes(endpoint.key))
  .map((endpoint) => ({
    key: endpoint.key,
    title: titles[endpoint.key] ?? endpoint.description,
    group: endpoint.key.split(".")[0],
    pathParams: [...endpoint.path.matchAll(/:([A-Za-z]+)/g)].map((match) => match[1]),
    queryParams: params[endpoint.key] ?? [],
    download: endpoint.key.endsWith(".download") || endpoint.key.endsWith(".manifest"),
    optionSources: optionSources[endpoint.key] ?? {},
  }));

export const queryByKey = new Map(queries.map((query) => [query.key, query]));
