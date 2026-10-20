export const messages = {
  ar: { app: "الأرشيف المركزي", dashboard: "لوحة التحكم", projects: "المشاريع", organizations: "المؤسسات", users: "المستخدمون", roles: "الأدوار", settings: "الإعدادات", packages: "الحزم", exports: "حزم التصدير", migrations: "الترحيل", operations: "العمليات", queries: "الاستعلامات", audit: "سجل التدقيق", search: "بحث", retry: "إعادة المحاولة", loading: "جارٍ التحميل…", empty: "لا توجد بيانات لعرضها.", error: "تعذر تحميل البيانات." },
  en: { app: "Central Archive", dashboard: "Dashboard", projects: "Projects", organizations: "Organizations", users: "Users", roles: "Roles", settings: "Settings", packages: "Packages", exports: "Export packages", migrations: "Migration", operations: "Operations", queries: "Queries", audit: "Audit log", search: "Search", retry: "Retry", loading: "Loading…", empty: "No data to display.", error: "Unable to load data." },
} as const;

export type Locale = keyof typeof messages;
