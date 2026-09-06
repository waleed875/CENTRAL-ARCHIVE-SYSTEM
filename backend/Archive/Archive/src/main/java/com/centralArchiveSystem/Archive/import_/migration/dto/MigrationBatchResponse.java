package com.centralArchiveSystem.Archive.import_.migration.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Response for a migration batch")
public record MigrationBatchResponse(
    @Schema(description = "Unique identifier") UUID id,
    @Schema(description = "Migration source ID") UUID migrationSourceId,
    @Schema(description = "Batch number") Integer batchNumber,
    @Schema(description = "Start timestamp") LocalDateTime startedAt,
    @Schema(description = "Completion timestamp") LocalDateTime completedAt,
    @Schema(description = "Batch status") String status,
    @Schema(description = "Number of projects migrated") Integer projectsCount,
    @Schema(description = "Number of files migrated") Integer filesCount,
    @Schema(description = "Notes") String notes
) {}
