package com.centralArchiveSystem.Archive.project.project.dto;

import com.centralArchiveSystem.Archive.project.project.Project;
import com.centralArchiveSystem.Archive.project.member.dto.MemberRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import jakarta.validation.Valid;

import java.util.List;
import java.util.UUID;

@Schema(description = "Request DTO for creating and updating projects")
public record ProjectRequest(
    @Schema(description = "Organization ID", example = "550e8400-e29b-41d4-a716-446655440000")
    @NotNull(message = "Organization ID is required") UUID organizationId,

    @Schema(description = "Project type ID") UUID projectTypeId,

    @Schema(description = "Confidentiality level ID") UUID confidentialityLevelId,

    @Schema(description = "Project number", example = "PRJ-2026-001")
    @NotBlank(message = "Project number is required")
    @Size(max = 50, message = "Project number must not exceed 50 characters") String projectNumber,

    @Schema(description = "Project code", example = "PRJ-2026-001-A")
    @Size(max = 50, message = "Project code must not exceed 50 characters") String projectCode,

    @Schema(description = "Project name", example = "Central Archive System")
    @NotBlank(message = "Project name is required")
    @Size(max = 255, message = "Project name must not exceed 255 characters") String name,

    @Schema(description = "Project year", example = "2026")
    @NotNull(message = "Year is required")
    @Min(value = 1900, message = "Year must be 1900 or later")
    @Max(value = 2100, message = "Year must be 2100 or earlier") Integer year,

    @Schema(description = "Executing entity")
    @Size(max = 255, message = "Executing entity must not exceed 255 characters") String executingEntity,

    @Schema(description = "Supervisor name")
    @Size(max = 255, message = "Supervisor name must not exceed 255 characters") String supervisorName,

    @Schema(description = "Project description") String description,

    @Schema(description = "Project notes") String notes,

    @Schema(description = "Table of contents") String tableOfContents,

    @Schema(description = "Keywords (comma-separated)") String keywords,

    @Schema(description = "Data completion status") Project.DataCompletionStatus dataCompletionStatus,

    @Schema(description = "Source type") Project.SourceType sourceType,

    @Schema(description = "Source reference") String sourceReference,

    @Schema(description = "Created by user") String createdBy,

    @Schema(description = "Optional project members created with the project. Manage later changes through the member endpoints.")
    List<@Valid MemberRequest> members
) {}
