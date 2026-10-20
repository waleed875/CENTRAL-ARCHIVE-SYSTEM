import { endpoints, type HttpMethod } from "./catalog";
import type { ReferenceSource } from "./reference-options";

export type FieldKind = "text" | "password" | "email" | "number" | "textarea" | "boolean" | "json" | "ids" | "file" | "files" | "members" | "organization-mapping";
export type OperationField = {
  name: string;
  label: string;
  kind?: FieldKind;
  required?: boolean;
  lookup?: string;
  enumKey?: string;
  lookupQuery?: Record<string, string>;
  pagination?: ReferenceSource["pagination"];
  dependsOn?: { field: string; param: string; pathParam?: boolean };
  valueField?: string;
  labelStyle?: "organization" | "project" | "code";
  multiple?: boolean;
  placeholder?: string;
};
export type OperationDefinition = {
  key: string;
  title: string;
  method: HttpMethod;
  body?: "json" | "form-data";
  group: string;
  pathParams: string[];
  fields: OperationField[];
  queryFields: OperationField[];
};

const f = (name: string, label: string, required = false, kind: FieldKind = "text", lookup?: string, options: Partial<OperationField> = {}): OperationField => ({ name, label, required, kind, lookup, ...options });
const nameAndDescription = [f("name", "الاسم", true), f("description", "الوصف", false, "textarea")];
const coded = [f("code", "الرمز", true), ...nameAndDescription];
const active = f("isActive", "نشط", false, "boolean");
const member = [f("personName", "اسم الشخص", true), f("organizationName", "اسم الجهة", true), f("roleName", "الدور", true), f("notes", "ملاحظات", false, "textarea")];
const project = [
  f("organizationId", "المؤسسة", true, "text", "organizations.all", { labelStyle: "organization" }),
  f("projectTypeId", "نوع المشروع", true, "text", "projectTypes.all"),
  f("confidentialityLevelId", "مستوى السرية", false, "text", "confidentialityLevels.all"),
  f("projectNumber", "رقم المشروع", true), f("projectCode", "رمز المشروع", true),
  f("name", "اسم المشروع", true), f("year", "السنة", true, "number"),
  f("executingEntity", "الجهة المنفذة"), f("supervisorName", "اسم المشرف"),
  f("description", "الوصف", false, "textarea"), f("notes", "ملاحظات", false, "textarea"),
  f("tableOfContents", "جدول المحتويات", false, "textarea"), f("keywords", "الكلمات المفتاحية"),
  f("dataCompletionStatus", "حالة اكتمال البيانات", false, "text", undefined, { enumKey: "dataCompletionStatus" }),
  f("members", "أعضاء المشروع", false, "members"),
];

const bodyFields: Record<string, OperationField[]> = {
  "auth.changePassword": [f("currentPassword", "كلمة المرور الحالية", true, "password"), f("newPassword", "كلمة المرور الجديدة", true, "password")],
  "organizations.create": [f("parentId", "المؤسسة الأم", false, "text", "organizations.all", { labelStyle: "organization" }), ...coded, f("type", "نوع المؤسسة", true, "text", undefined, { enumKey: "organizationType" })],
  "organizations.update": [...nameAndDescription, active],
  "roles.create": [...coded, active],
  "roles.update": [...nameAndDescription, active],
  "users.create": [f("username", "اسم المستخدم", true), f("password", "كلمة المرور", true, "password"), f("fullName", "الاسم الكامل", true), f("email", "البريد الإلكتروني", true, "email"), f("organizationId", "المؤسسة", true, "text", "organizations.all", { labelStyle: "organization" }), f("maxConfidentialityLevelId", "أعلى مستوى سرية", false, "text", "confidentialityLevels.all"), f("roleIds", "الأدوار", false, "ids", "roles.all", { lookupQuery: { isActive: "true" }, multiple: true })],
  "users.update": [f("fullName", "الاسم الكامل"), f("email", "البريد الإلكتروني", false, "email"), f("organizationId", "المؤسسة", false, "text", "organizations.all", { labelStyle: "organization" }), f("maxConfidentialityLevelId", "أعلى مستوى سرية", false, "text", "confidentialityLevels.all"), f("status", "الحالة", false, "text", undefined, { enumKey: "userStatus" })],
  "confidentialityLevels.create": [...coded, f("levelOrder", "ترتيب المستوى", true, "number")],
  "confidentialityLevels.update": [...nameAndDescription, f("levelOrder", "ترتيب المستوى", false, "number")],
  "projectTypes.create": coded,
  "projectTypes.update": [...nameAndDescription, active],
  "fileCategories.create": [...coded, active],
  "fileCategories.update": [...nameAndDescription, active],
  "fileTypes.create": [f("categoryId", "فئة الملف", true, "text", "fileCategories.all", { lookupQuery: { isActive: "true" } }), ...coded, f("defaultExtension", "الامتداد الافتراضي", true), active],
  "fileTypes.update": [...nameAndDescription, f("defaultExtension", "الامتداد الافتراضي"), active],
  "projects.create": project,
  "projects.update": project.filter((field) => field.name !== "members").map((field) => ({ ...field, required: false })),
  "projects.correction": [f("review_notes", "ملاحظات المراجعة", true, "textarea")],
  "projects.reject": [f("rejection_reason", "سبب الرفض", true, "textarea")],
  "projectMembers.create": member,
  "projectMembers.update": member.map((field) => ({ ...field, required: false })),
  "files.upload": [f("file", "الملف", true, "file"), f("categoryId", "فئة الملف", true, "text", "fileCategories.all", { lookupQuery: { isActive: "true" } }), f("typeId", "نوع الملف", true, "text", "fileTypes.byCategory", { lookupQuery: { isActive: "true" }, dependsOn: { field: "categoryId", param: "id", pathParam: true } }), f("confidentialityLevelId", "مستوى السرية", false, "text", "confidentialityLevels.all")],
  "files.uploadBatch": [f("files", "الملفات", true, "files"), f("categoryId", "فئة الملف", true, "text", "fileCategories.all", { lookupQuery: { isActive: "true" } }), f("typeId", "نوع الملف", true, "text", "fileTypes.byCategory", { lookupQuery: { isActive: "true" }, dependsOn: { field: "categoryId", param: "id", pathParam: true } }), f("confidentialityLevelId", "مستوى السرية", false, "text", "confidentialityLevels.all")],
  "files.update": [f("categoryId", "فئة الملف", false, "text", "fileCategories.all"), f("typeId", "نوع الملف", false, "text", "fileTypes.byCategory", { dependsOn: { field: "categoryId", param: "id", pathParam: true } }), f("confidentialityLevelId", "مستوى السرية", false, "text", "confidentialityLevels.all")],
  "packages.receive": [f("package_file", "ملف الحزمة (.pkg / .zip / .nxpkg)", true, "file"), f("passphrase", "كلمة مرور الحزمة المشفرة", false, "password")],
  "migrationSources.create": [f("name", "اسم المصدر", true), f("sourceType", "نوع المصدر", true, "text", undefined, { enumKey: "migrationSourceType" }), f("officeName", "اسم المكتب", true), f("databaseName", "اسم قاعدة البيانات"), f("host", "عنوان الخادم"), f("port", "المنفذ", false, "number"), f("username", "اسم مستخدم قاعدة البيانات"), f("password", "كلمة مرور قاعدة البيانات", false, "password"), f("description", "الوصف", false, "textarea")],
  "migrationSources.update": [f("name", "اسم المصدر"), f("sourceType", "نوع المصدر", false, "text", undefined, { enumKey: "migrationSourceType" }), f("officeName", "اسم المكتب"), f("databaseName", "اسم قاعدة البيانات"), f("host", "عنوان الخادم"), f("port", "المنفذ", false, "number"), f("username", "اسم مستخدم قاعدة البيانات"), f("password", "كلمة مرور قاعدة البيانات", false, "password"), f("description", "الوصف", false, "textarea")],
  "migrationBatches.create": [f("batchNumber", "رقم الدفعة", true), f("notes", "ملاحظات", false, "textarea")],
  "migrationBatches.update": [f("notes", "ملاحظات", false, "textarea")],
  "migrationBatches.run": [],
  "migrationBatches.complete": [f("status", "الحالة", true, "text", undefined, { enumKey: "migrationBatchCompletionStatus" }), f("notes", "ملاحظات", false, "textarea")],
  "exportPackages.create": [f("projectIds", "المشاريع", true, "ids", "projects.list", { pagination: { pageParam: "page", sizeParam: "size", pageSize: 100 }, labelStyle: "project", multiple: true })],
};

const titles: Record<string, string> = {
  "auth.changePassword": "تغيير كلمة المرور", "organizations.create": "إنشاء مؤسسة", "organizations.update": "تعديل مؤسسة", "organizations.delete": "حذف مؤسسة",
  "roles.create": "إنشاء دور", "roles.update": "تعديل دور", "roles.delete": "حذف دور",
  "users.create": "إنشاء مستخدم", "users.update": "تعديل مستخدم", "users.delete": "حذف مستخدم", "users.addRole": "إضافة دور لمستخدم", "users.removeRole": "إزالة دور من مستخدم",
  "confidentialityLevels.create": "إنشاء مستوى سرية", "confidentialityLevels.update": "تعديل مستوى سرية", "confidentialityLevels.delete": "حذف مستوى سرية",
  "projectTypes.create": "إنشاء نوع مشروع", "projectTypes.update": "تعديل نوع مشروع", "projectTypes.delete": "حذف نوع مشروع",
  "fileCategories.create": "إنشاء فئة ملفات", "fileCategories.update": "تعديل فئة ملفات", "fileCategories.delete": "حذف فئة ملفات",
  "fileTypes.create": "إنشاء نوع ملف", "fileTypes.update": "تعديل نوع ملف", "fileTypes.delete": "حذف نوع ملف",
  "projects.create": "إنشاء مشروع", "projects.update": "تعديل مشروع", "projects.newVersion": "إنشاء نسخة جديدة", "projects.submit": "إرسال للمراجعة", "projects.correction": "طلب تصحيح", "projects.approve": "اعتماد مشروع", "projects.reject": "رفض مشروع", "projects.archive": "أرشفة مشروع",
  "projectMembers.create": "إضافة عضو للمشروع", "projectMembers.update": "تعديل عضو", "projectMembers.delete": "حذف عضو",
  "files.upload": "رفع ملف", "files.uploadBatch": "رفع ملفات", "files.update": "تعديل بيانات ملف", "files.restore": "استعادة ملف", "files.delete": "حذف ملف",
  "packages.receive": "استقبال حزمة",
  "migrationSources.create": "إضافة مصدر ترحيل", "migrationSources.update": "تعديل مصدر ترحيل", "migrationSources.delete": "حذف مصدر ترحيل",
  "migrationBatches.create": "إنشاء دفعة ترحيل", "migrationBatches.update": "تعديل دفعة ترحيل", "migrationBatches.run": "تشغيل دفعة الترحيل", "migrationBatches.complete": "إكمال دفعة الترحيل",
  "exportPackages.create": "إنشاء حزمة تصدير",
};

const queryFields: Record<string, OperationField[]> = {
  "users.addRole": [f("roleId", "الدور", true, "text", "roles.all", { lookupQuery: { isActive: "true" } })],
  "files.delete": [f("reason", "سبب الحذف", true, "textarea")],
};

export const operations: OperationDefinition[] = endpoints
  .filter((endpoint) => endpoint.method !== "GET" && !["auth.refresh", "auth.logout", "migrationSettings.update", "projects.delete", "packages.verify", "packages.import"].includes(endpoint.key))
  .map((endpoint) => ({
    key: endpoint.key,
    title: titles[endpoint.key] ?? endpoint.description,
    method: endpoint.method,
    body: endpoint.body,
    group: endpoint.key.split(".")[0],
    pathParams: [...endpoint.path.matchAll(/:([A-Za-z]+)/g)].map((match) => match[1]),
    fields: bodyFields[endpoint.key] ?? [],
    queryFields: queryFields[endpoint.key] ?? [],
  }));

export const operationByKey = new Map(operations.map((operation) => [operation.key, operation]));
