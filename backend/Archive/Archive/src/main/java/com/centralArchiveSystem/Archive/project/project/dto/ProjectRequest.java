package com.centralArchiveSystem.Archive.project.project.dto;

import com.centralArchiveSystem.Archive.project.project.Project;
import com.centralArchiveSystem.Archive.project.member.dto.MemberCreateRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import jakarta.validation.Valid;

import java.util.List;
import java.util.UUID;

@Schema(description = "Request DTO for manually creating projects. Provenance and creator are assigned by the server.")
public record ProjectRequest(
    @Schema(description = "Organization ID", example = "550e8400-e29b-41d4-a716-446655440000")
    @NotNull(message = "حقل المؤسسة مطلوب") UUID organizationId,

    @Schema(description = "Project type ID") UUID projectTypeId,

    @Schema(description = "Confidentiality level ID") UUID confidentialityLevelId,

    @Schema(description = "Project number", example = "PRJ-2026-001")
    @NotBlank(message = "حقل رقم المشروع مطلوب")
    @Size(max = 50, message = "رقم المشروع يجب ألا يتجاوز 50 حرفاً") String projectNumber,

    @Schema(description = "Project code", example = "PRJ-2026-001-A")
    @Size(max = 50, message = "رمز المشروع يجب ألا يتجاوز 50 حرفاً") String projectCode,

    @Schema(description = "Project name", example = "Central Archive System")
    @NotBlank(message = "حقل اسم المشروع مطلوب")
    @Size(max = 250, message = "اسم المشروع يجب ألا يتجاوز 250 حرفاً") String name,

    @Schema(description = "Project year", example = "2026")
    @NotNull(message = "حقل السنة مطلوب")
    @Min(value = 1900, message = "السنة يجب ألا تقل عن 1900")
    @Max(value = 2100, message = "السنة يجب ألا تزيد عن 2100") Integer year,

    @Schema(description = "Executing entity")
    @Size(max = 200, message = "الجهة المنفذة يجب ألا تتجاوز 200 حرف") String executingEntity,

    @Schema(description = "Supervisor name")
    @Size(max = 200, message = "اسم المشرف يجب ألا يتجاوز 200 حرف") String supervisorName,

    @Schema(description = "Project description") String description,

    @Schema(description = "Project notes") String notes,

    @Schema(description = "Table of contents") String tableOfContents,

    @Schema(description = "Keywords (comma-separated)") String keywords,

    @Schema(description = "Data completion status") Project.DataCompletionStatus dataCompletionStatus,

    @Schema(description = "Optional project members created with the project. Manage later changes through the member endpoints.")
    List<@Valid MemberCreateRequest> members
) {}
