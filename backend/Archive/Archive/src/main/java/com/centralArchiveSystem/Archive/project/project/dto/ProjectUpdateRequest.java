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
    @Size(max = 50, message = "Project number must not exceed 50 characters") String projectNumber,
    @Size(max = 50, message = "Project code must not exceed 50 characters") String projectCode,
    @Size(max = 255, message = "Project name must not exceed 255 characters") String name,
    @Min(value = 1900, message = "Year must be 1900 or later")
    @Max(value = 2100, message = "Year must be 2100 or earlier") Integer year,
    @Size(max = 255, message = "Executing entity must not exceed 255 characters") String executingEntity,
    @Size(max = 255, message = "Supervisor name must not exceed 255 characters") String supervisorName,
    String description,
    String notes,
    String tableOfContents,
    String keywords,
    Project.DataCompletionStatus dataCompletionStatus
) {}
