package com.centralArchiveSystem.Archive.project.project.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

@Schema(description = "Project statistics response")
public record ProjectStatsResponse(
    @Schema(description = "Total number of projects") long totalProjects,
    @Schema(description = "Count of projects by status") Map<String, Long> projectsByStatus,
    @Schema(description = "Count of projects by year") Map<Integer, Long> projectsByYear,
    @Schema(description = "Count of latest versions only") long latestVersionCount,
    @Schema(description = "Count of archived projects") long archivedCount
) {}
