package com.centralArchiveSystem.Archive.project.project.dto;

import com.centralArchiveSystem.Archive.project.project.Project;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Search parameters for projects")
public record ProjectSearchRequest(
    @Schema(description = "Search term to filter by name, number, code, description, or keywords") String searchTerm,
    @Schema(description = "Organization ID to filter by") UUID organizationId,
    @Schema(description = "Project status to filter by") Project.ProjectStatus status,
    @Schema(description = "Year to filter by") Integer year,
    @Schema(description = "Project type ID to filter by") UUID projectTypeId,
    @Schema(description = "Confidentiality level ID to filter by") UUID confidentialityLevelId,
    @Schema(description = "Project source type to filter by") Project.SourceType sourceType,
    @Schema(description = "Stable project UID to filter by") UUID projectUid,
    @Schema(description = "Creator user ID to filter by") UUID createdBy,
    @Schema(description = "Approver user ID to filter by") UUID approvedBy,
    @Schema(description = "Whether to return only the latest version") Boolean isLatestVersion,
    @Schema(description = "Page number (0-based)", example = "0") Integer page,
    @Schema(description = "Page size", example = "20") Integer size,
    @Schema(description = "Sort field", example = "createdAt") String sortBy,
    @Schema(description = "Sort direction (asc/desc)", example = "desc") String sortDirection
) {
    public ProjectSearchRequest {
        if (isLatestVersion == null) isLatestVersion = true;
        if (page == null) page = 0;
        if (size == null) size = 20;
        if (sortBy == null) sortBy = "createdAt";
        if (sortDirection == null) sortDirection = "desc";
    }
}
