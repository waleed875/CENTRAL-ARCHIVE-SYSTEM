package com.centralArchiveSystem.Archive.import_.package_.export.export;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * Without versionScope, retain the legacy v1 behavior: export only the exact
 * row IDs supplied. New clients explicitly request ALL for a versioned v2 .pkg.
 * File content and integrity checks are mandatory, not client-disableable.
 */
@Schema(description = "Selected projects and optional version-aware PKG export settings")
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

    public boolean allVersions() {
        return "ALL".equals(versionScope);
    }

    public void validateOptions() {
        if (projectIds == null || projectIds.isEmpty() || projectIds.contains(null)) {
            throw new IllegalArgumentException("يجب تحديد مشروع واحد على الأقل بدون عناصر فارغة");
        }
        if (projectIds.size() > 200) {
            throw new IllegalArgumentException("يمكن اختيار 200 مشروع كحد أقصى في عملية واحدة");
        }
        if (versionScope != null && !versionScope.equals("ALL") && !versionScope.equals("EXACT")) {
            throw new IllegalArgumentException("versionScope must be ALL or EXACT");
        }
        if (format != null && !"PKG".equals(format)) {
            throw new IllegalArgumentException("Only PKG export is supported");
        }
        if (contents != null && (!Boolean.TRUE.equals(contents.data()) || !Boolean.TRUE.equals(contents.files()))) {
            throw new IllegalArgumentException("PKG export must include both data and files");
        }
        if (protection != null && (!Boolean.TRUE.equals(protection.enabled())
                || !"SHA256".equals(protection.integrity()))) {
            throw new IllegalArgumentException("PKG export requires enabled SHA256 integrity protection");
        }
    }

    public record ExportContents(Boolean data, Boolean files) {}
    public record ExportProtection(Boolean enabled, String integrity) {}
}
