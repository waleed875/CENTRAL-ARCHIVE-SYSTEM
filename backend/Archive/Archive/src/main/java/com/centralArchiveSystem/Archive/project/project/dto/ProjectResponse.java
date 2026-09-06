package com.centralArchiveSystem.Archive.project.project.dto;

import com.centralArchiveSystem.Archive.project.project.Project;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Full project response DTO")
public record ProjectResponse(
    @Schema(description = "Project ID") UUID id,
    @Schema(description = "Project UID") String projectUid,
    @Schema(description = "Version number") Integer versionNo,
    @Schema(description = "Previous project version ID") UUID previousProjectId,
    @Schema(description = "Whether this is the latest version") Boolean isLatestVersion,
    @Schema(description = "Organization ID") UUID organizationId,
    @Schema(description = "Project type ID") UUID projectTypeId,
    @Schema(description = "Confidentiality level ID") UUID confidentialityLevelId,
    @Schema(description = "Project number") String projectNumber,
    @Schema(description = "Project code") String projectCode,
    @Schema(description = "Project name") String name,
    @Schema(description = "Project year") Integer year,
    @Schema(description = "Executing entity") String executingEntity,
    @Schema(description = "Supervisor name") String supervisorName,
    @Schema(description = "Project description") String description,
    @Schema(description = "Project notes") String notes,
    @Schema(description = "Table of contents") String tableOfContents,
    @Schema(description = "Keywords") String keywords,
    @Schema(description = "Project status") Project.ProjectStatus status,
    @Schema(description = "Data completion status") Project.DataCompletionStatus dataCompletionStatus,
    @Schema(description = "Review notes") String reviewNotes,
    @Schema(description = "Source type") Project.SourceType sourceType,
    @Schema(description = "Source reference") String sourceReference,
    @Schema(description = "Created by user") String createdBy,
    @Schema(description = "Approved by user") String approvedBy,
    @Schema(description = "Approval timestamp") LocalDateTime approvedAt,
    @Schema(description = "Creation timestamp") LocalDateTime createdAt,
    @Schema(description = "Last update timestamp") LocalDateTime updatedAt
) {
    public static ProjectResponse fromEntity(Project project) {
        return new ProjectResponse(
            project.getId(),
            project.getProjectUid(),
            project.getVersionNo(),
            project.getPreviousProjectId(),
            project.getIsLatestVersion(),
            project.getOrganizationId(),
            project.getProjectTypeId(),
            project.getConfidentialityLevelId(),
            project.getProjectNumber(),
            project.getProjectCode(),
            project.getName(),
            project.getYear(),
            project.getExecutingEntity(),
            project.getSupervisorName(),
            project.getDescription(),
            project.getNotes(),
            project.getTableOfContents(),
            project.getKeywords(),
            project.getStatus(),
            project.getDataCompletionStatus(),
            project.getReviewNotes(),
            project.getSourceType(),
            project.getSourceReference(),
            project.getCreatedBy(),
            project.getApprovedBy(),
            project.getApprovedAt(),
            project.getCreatedAt(),
            project.getUpdatedAt()
        );
    }
}
