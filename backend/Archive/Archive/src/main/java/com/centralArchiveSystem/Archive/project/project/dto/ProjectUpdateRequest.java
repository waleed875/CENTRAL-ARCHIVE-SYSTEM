package com.centralArchiveSystem.Archive.project.project.dto;

import com.centralArchiveSystem.Archive.project.project.Project;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.UUID;

@Schema(description = "Partial project metadata update request")
public record ProjectUpdateRequest(
    @Schema(description = "Organization ID") UUID organizationId,
    @Schema(description = "Project type ID") UUID projectTypeId,
    @Schema(description = "Confidentiality level ID") UUID confidentialityLevelId,
    @Size(max = 50, message = "رقم المشروع يجب ألا يتجاوز 50 حرفاً") String projectNumber,
    @Size(max = 50, message = "رمز المشروع يجب ألا يتجاوز 50 حرفاً") String projectCode,
    @Size(max = 250, message = "اسم المشروع يجب ألا يتجاوز 250 حرفاً") String name,
    @Min(value = 1900, message = "السنة يجب ألا تقل عن 1900")
    @Max(value = 2100, message = "السنة يجب ألا تزيد عن 2100") Integer year,
    @Size(max = 200, message = "الجهة المنفذة يجب ألا تتجاوز 200 حرف") String executingEntity,
    @Size(max = 200, message = "اسم المشرف يجب ألا يتجاوز 200 حرف") String supervisorName,
    String description,
    String notes,
    String tableOfContents,
    String keywords,
    Project.DataCompletionStatus dataCompletionStatus
) {}
