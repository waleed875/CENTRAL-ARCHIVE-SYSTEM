package com.centralArchiveSystem.Archive.dashboard.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

@Schema(description = "Dashboard statistics response")
public record DashboardStatsResponse(
    @Schema(description = "Organization statistics") OrganizationStats organizations,
    @Schema(description = "User statistics") UserStats users,
    @Schema(description = "Project statistics") ProjectStats projects,
    @Schema(description = "File statistics") FileStats files,
    @Schema(description = "Package statistics") PackageStats packages,
    @Schema(description = "Total pending reviews (UNDER_REVIEW projects)") long pendingReviews
) {
    @Schema(description = "Organization statistics")
    public record OrganizationStats(
        @Schema(description = "Total number of organizations") long total,
        @Schema(description = "Number of active organizations") long active
   ) {}

    @Schema(description = "User statistics")
    public record UserStats(
        @Schema(description = "Total number of users") long total,
        @Schema(description = "Number of active users") long active
    ) {}

    @Schema(description = "Project statistics")
    public record ProjectStats(
        @Schema(description = "Total number of projects (latest versions only)") long total,
        @Schema(description = "Count of projects grouped by status") Map<String, Long> byStatus,
        @Schema(description = "Count of projects grouped by year") Map<Integer, Long> byYear,
        @Schema(description = "Count of projects grouped by source type") Map<String, Long> bySource
    ) {}

    @Schema(description = "File statistics")
    public record FileStats(
        @Schema(description = "Total number of active files") long total,
        @Schema(description = "Total size of all active files in bytes") Long totalSizeBytes
    ) {}

    @Schema(description = "Package statistics")
    public record PackageStats(
        @Schema(description = "Total received packages (all statuses)") long received,
        @Schema(description = "Packages pending import (RECEIVED or VERIFIED status)") long pendingImport
    ) {}
}
