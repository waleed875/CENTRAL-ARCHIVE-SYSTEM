package com.centralArchiveSystem.Archive.common.enums;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Arabic presentation labels, separate from stable wire-format codes. */
public final class ArabicEnumLabels {
    private ArabicEnumLabels() {}
    private static final Map<String, String> LABELS = Map.ofEntries(
        Map.entry("DRAFT", "مسودة"), Map.entry("IMPORTED", "مستورد"),
        Map.entry("UNDER_REVIEW", "قيد المراجعة"), Map.entry("NEEDS_CORRECTION", "يحتاج إلى تصحيح"),
        Map.entry("REJECTED", "مرفوض"), Map.entry("APPROVED", "معتمد"), Map.entry("ARCHIVED", "مؤرشف"),
        Map.entry("COMPLETE", "مكتمل"), Map.entry("INCOMPLETE", "غير مكتمل"),
        Map.entry("MANUAL", "إدخال يدوي"), Map.entry("BRANCH_PACKAGE", "حزمة فرع"), Map.entry("MIGRATION", "ترحيل"),
        Map.entry("ACTIVE", "نشط"), Map.entry("DISABLED", "معطل"), Map.entry("LOCKED", "مقفل"), Map.entry("DELETED", "محذوف"),
        Map.entry("COMPANY", "شركة"), Map.entry("FACTORY", "مصنع"), Map.entry("LINE", "خط"), Map.entry("DEPARTMENT", "قسم"),
        Map.entry("POSTGRESQL", "قاعدة PostgreSQL"), Map.entry("LEGACY_DB", "قاعدة النظام القديم"),
        Map.entry("PLANNED", "مخطط"), Map.entry("RUNNING", "قيد التنفيذ"), Map.entry("SUCCESS", "ناجح"),
        Map.entry("FAILED", "فاشل"), Map.entry("PARTIAL", "نجاح جزئي"), Map.entry("RECEIVED", "مستلم"),
        Map.entry("VERIFIED", "تم التحقق"), Map.entry("SEALED_VERIFIED", "تم التحقق من التوقيع"),
        Map.entry("DECRYPTED", "تم فك التشفير"), Map.entry("DUPLICATE", "مكرر"),
        Map.entry("REJECT", "رفض"), Map.entry("APPROVE", "اعتماد"), Map.entry("ARCHIVE", "أرشفة"),
        Map.entry("IMPORT", "استيراد"), Map.entry("CREATE", "إنشاء"), Map.entry("UPDATE", "تعديل"),
        Map.entry("DELETE", "حذف"), Map.entry("READ", "قراءة"), Map.entry("EXECUTE", "تنفيذ"),
        Map.entry("Auth", "المصادقة"), Map.entry("User", "مستخدم"), Map.entry("Role", "دور"),
        Map.entry("Organization", "مؤسسة"), Map.entry("ConfidentialityLevel", "مستوى سرية"),
        Map.entry("Project", "مشروع"), Map.entry("ProjectMember", "عضو مشروع"), Map.entry("ProjectType", "نوع مشروع"),
        Map.entry("FileCategory", "فئة ملف"), Map.entry("FileType", "نوع ملف"), Map.entry("File", "ملف"),
        Map.entry("BranchPackage", "حزمة فرع"), Map.entry("Export", "تصدير"), Map.entry("Migration", "ترحيل"),
        Map.entry("MigrationSettings", "إعدادات الترحيل"), Map.entry("AuditLog", "سجل التدقيق"),
        Map.entry("Dashboard", "لوحة التحكم"), Map.entry("Enums", "القيم المقيدة"), Map.entry("ReviewQueue", "قائمة المراجعة"),
        Map.entry("createdAt", "تاريخ الإنشاء"), Map.entry("updatedAt", "تاريخ التعديل"),
        Map.entry("submittedAt", "تاريخ الإرسال"), Map.entry("name", "الاسم"), Map.entry("projectNumber", "رقم المشروع"),
        Map.entry("projectCode", "رمز المشروع"), Map.entry("year", "السنة"), Map.entry("status", "الحالة"),
        Map.entry("versionNo", "رقم الإصدار"), Map.entry("asc", "تصاعدي"), Map.entry("desc", "تنازلي"),
        Map.entry("PROJECT", "مشروع"), Map.entry("FILE", "ملف"), Map.entry("PACKAGE", "حزمة"),
        Map.entry("PROJECT_CREATED", "إنشاء مشروع"), Map.entry("PROJECT_IMPORTED", "استيراد مشروع"),
        Map.entry("PROJECT_SUBMITTED_FOR_REVIEW", "إرسال مشروع للمراجعة"),
        Map.entry("PROJECT_CORRECTION_REQUESTED", "طلب تصحيح مشروع"), Map.entry("PROJECT_REJECTED", "رفض مشروع"),
        Map.entry("PROJECT_APPROVED", "اعتماد مشروع"), Map.entry("PROJECT_ARCHIVED", "أرشفة مشروع"),
        Map.entry("FILE_UPLOADED", "رفع ملف")
    );

    private static String label(String code) {
        if (code.startsWith("PACKAGE_")) return "حزمة: " + LABELS.getOrDefault(code.substring(8), code);
        return LABELS.getOrDefault(code, code);
    }

    public static Map<String, Map<String, String>> forCatalog(Map<String, List<String>> catalog) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        catalog.forEach((group, codes) -> {
            Map<String, String> translated = new LinkedHashMap<>();
            codes.forEach(code -> translated.put(code, label(code)));
            result.put(group, translated);
        });
        return result;
    }
}
