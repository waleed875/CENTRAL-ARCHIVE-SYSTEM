package com.centralArchiveSystem.Archive.import_.package_.export.export;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * The server always enforces classification clearance and SHA-256 integrity.
 * PKG is importable; ZIP delivers original files; XLSX/PDF are metadata-only reports.
 * Absent format/versionScope retains the old exact-row v1 PKG behavior.
 */
@Schema(description = "Selected projects and export format, versions and protection")
public record ExportRequest(
        @NotEmpty(message = "يجب تحديد مشروع واحد على الأقل")
        List<@NotNull(message = "لا تُقبل عناصر فارغة داخل القائمة") UUID> projectIds,
        String versionScope,
        @Valid ExportContents contents,
        String format,
        @Valid ExportProtection protection) {

    public ExportRequest(List<UUID> projectIds) {
        this(projectIds, null, null, null, null);
    }

    public boolean allVersions() { return "ALL".equals(versionScope); }

    public String selectedFormat() {
        return format == null ? "PKG" : format;
    }

    public boolean encrypted() {
        return protection != null && "PASSPHRASE".equals(protection.encryptionMode());
    }

    public String exportPassphrase() {
        return protection == null ? null : protection.passphrase();
    }

    public void validateOptions() {
        if (projectIds == null || projectIds.isEmpty()
                || projectIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("يجب تحديد مشروع واحد على الأقل بدون عناصر فارغة");
        }
        if (projectIds.size() > 200) {
            throw new IllegalArgumentException("يمكن اختيار 200 مشروع كحد أقصى في عملية واحدة");
        }
        if (versionScope != null && !versionScope.equals("ALL") && !versionScope.equals("EXACT")) {
            throw new IllegalArgumentException("versionScope must be ALL or EXACT");
        }
        String selected = selectedFormat();
        if (!List.of("PKG", "ZIP", "XLSX", "PDF").contains(selected)) {
            throw new IllegalArgumentException("نوع التصدير غير مدعوم");
        }
        if (contents != null) {
            boolean data = Boolean.TRUE.equals(contents.data());
            boolean files = Boolean.TRUE.equals(contents.files());
            if (!data || (List.of("PKG", "ZIP").contains(selected) != files)) {
                throw new IllegalArgumentException("حزم PKG/ZIP تتطلب البيانات والملفات؛ تقارير Excel/PDF تحتوي البيانات فقط");
            }
        }
        if (protection != null) {
            if (!Boolean.TRUE.equals(protection.enabled())
                    || !"SHA256".equals(protection.integrity())) {
                throw new IllegalArgumentException("التحقق من صلاحيات التصدير وسلامة SHA-256 إلزامي");
            }
            String mode = protection.encryptionMode();
            if (mode != null && !List.of("NONE", "PASSPHRASE").contains(mode)) {
                throw new IllegalArgumentException("وضع تشفير الحزمة غير مدعوم");
            }
            if (encrypted()) {
                if (!"PKG".equals(selected)) {
                    throw new IllegalArgumentException("التشفير متاح لحزمة PKG القابلة للاستيراد فقط");
                }
                String passphrase = exportPassphrase();
                if (passphrase == null || passphrase.length() < 12 || passphrase.length() > 256
                        || passphrase.isBlank()) {
                    throw new IllegalArgumentException("كلمة مرور التشفير يجب أن تتكون من 12 إلى 256 حرفًا");
                }
            } else if (protection.passphrase() != null && !protection.passphrase().isEmpty()) {
                throw new IllegalArgumentException("كلمة المرور مسموحة في وضع التشفير فقط");
            }
        }
    }

    public record ExportContents(Boolean data, Boolean files) {}

    public record ExportProtection(Boolean enabled, String integrity,
                                   String encryptionMode, String passphrase) {
        public ExportProtection(Boolean enabled, String integrity) {
            this(enabled, integrity, null, null);
        }
        @Override public String toString() {
            return "ExportProtection[enabled=" + enabled + ", integrity=" + integrity
                    + ", encryptionMode=" + encryptionMode + ", passphrase=[REDACTED]]";
        }
    }

    @Override public String toString() {
        return "ExportRequest[projectIds=" + projectIds + ", versionScope=" + versionScope
                + ", format=" + format + ", protection=[REDACTED]]";
    }
}
