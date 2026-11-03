package com.centralArchiveSystem.Archive.common.i18n;

import java.util.Map;

/** Presentation-only translations. Stored enum values and error codes remain unchanged. */
public final class ArabicApiMessages {
    private ArabicApiMessages() {}
    private static final Map<String, String> MESSAGES = Map.ofEntries(
        Map.entry("Login successful", "تم تسجيل الدخول بنجاح"),
        Map.entry("Logout successful", "تم تسجيل الخروج"),
        Map.entry("Token refreshed successfully", "تم تجديد الجلسة"),
        Map.entry("Password changed successfully", "تم تغيير كلمة المرور"),
        Map.entry("User created successfully", "تم إنشاء المستخدم"),
        Map.entry("User updated successfully", "تم تحديث المستخدم"),
        Map.entry("Role created successfully", "تم إنشاء الدور"),
        Map.entry("Role updated successfully", "تم تحديث الدور"),
        Map.entry("Organization created", "تم إنشاء المؤسسة"),
        Map.entry("Organization updated", "تم تحديث المؤسسة"),
        Map.entry("Organization deleted", "تم حذف المؤسسة"),
        Map.entry("Confidentiality level created", "تم إنشاء مستوى السرية"),
        Map.entry("Confidentiality level updated", "تم تحديث مستوى السرية"),
        Map.entry("Project created successfully", "تم إنشاء المشروع"),
        Map.entry("Project updated successfully", "تم تحديث المشروع"),
        Map.entry("New project version created", "تم إنشاء إصدار جديد للمشروع"),
        Map.entry("Project submitted for review", "تم إرسال المشروع للمراجعة"),
        Map.entry("Correction requested", "تم طلب التصحيح"),
        Map.entry("Project approved", "تم اعتماد المشروع"),
        Map.entry("Project rejected", "تم رفض المشروع"),
        Map.entry("Project archived", "تمت أرشفة المشروع"),
        Map.entry("Project type created", "تم إنشاء نوع المشروع"),
        Map.entry("Project type updated", "تم تحديث نوع المشروع"),
        Map.entry("Member added", "تمت إضافة العضو"),
        Map.entry("Member updated", "تم تحديث العضو"),
        Map.entry("File uploaded successfully", "تم رفع الملف"),
        Map.entry("Batch upload completed", "اكتملت معالجة رفع الملفات"),
        Map.entry("File metadata updated successfully", "تم تحديث بيانات الملف"),
        Map.entry("File deleted successfully", "تم حذف الملف"),
        Map.entry("File restored successfully", "تمت استعادة الملف"),
        Map.entry("File category created successfully", "تم إنشاء فئة الملف"),
        Map.entry("File category updated successfully", "تم تحديث فئة الملف"),
        Map.entry("File category deactivated successfully", "تم تعطيل فئة الملف"),
        Map.entry("File type created successfully", "تم إنشاء نوع الملف"),
        Map.entry("File type updated successfully", "تم تحديث نوع الملف"),
        Map.entry("File type deactivated successfully", "تم تعطيل نوع الملف"),
        Map.entry("Package received", "تم استلام الحزمة"),
        Map.entry("Package verified", "تم التحقق من الحزمة"),
        Map.entry("Package verification failed", "فشل التحقق من الحزمة"),
        Map.entry("Import process started", "بدأت عملية الاستيراد"),
        Map.entry("Export package created", "تم إنشاء حزمة التصدير"),
        Map.entry("Migration source created", "تم إنشاء مصدر الترحيل"),
        Map.entry("Migration source updated", "تم تحديث مصدر الترحيل"),
        Map.entry("Migration source deleted", "تم حذف مصدر الترحيل"),
        Map.entry("Migration batch created", "تم إنشاء دفعة الترحيل"),
        Map.entry("Migration batch updated", "تم تحديث دفعة الترحيل"),
        Map.entry("Migration batch started", "بدأت دفعة الترحيل"),
        Map.entry("Migration batch completed", "اكتملت دفعة الترحيل")
    );
    public static String translate(String text) {
        return text == null ? null : MESSAGES.getOrDefault(text, text);
    }
}
