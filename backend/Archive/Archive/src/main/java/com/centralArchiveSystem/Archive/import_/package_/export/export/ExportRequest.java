package com.centralArchiveSystem.Archive.import_.package_.export.export;

import jakarta.validation.constraints.NotNull;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

@Schema(description = "Request DTO for exporting a package. Exporter and provenance are assigned by the server.")
public record ExportRequest(
    @Schema(description = "Project IDs to include in the export", required = true)
    @NotEmpty(message = "يجب تحديد مشروع واحد على الأقل")
    List<@NotNull(message = "لا تُقبل عناصر فارغة داخل القائمة") UUID> projectIds
) {}
