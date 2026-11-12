package com.centralArchiveSystem.Archive.import_.migration.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Response for a migration batch")
public record MigrationBatchResponse(
    @Schema(description = "Unique identifier") UUID id,
    @Schema(description = "Migration source ID") UUID migrationSourceId,
    @Schema(description = "Batch number") String batchNumber,
    @Schema(description = "Start timestamp") LocalDateTime startedAt,
    @Schema(description = "Completion timestamp") LocalDateTime completedAt,
    @Schema(description = "Batch status") String status,
    @Schema(description = "Number of projects migrated") Integer projectsCount,
    @Schema(description = "Number of files migrated") Integer filesCount,
    @Schema(description = "Projects remaining in the migration source") Integer remainingProjectsCount,
    @Schema(description = "Files remaining in the migration source") Integer remainingFilesCount,
    @Schema(description = "Whether a safe stop has been requested") boolean cancelRequested,
    @Schema(description = "Notes") String notes
) {
    public MigrationBatchResponse(UUID id, UUID migrationSourceId, String batchNumber,
                                   LocalDateTime startedAt, LocalDateTime completedAt, String status,
                                   Integer projectsCount, Integer filesCount, String notes) {
        this(id, migrationSourceId, batchNumber, startedAt, completedAt, status,
                projectsCount, filesCount, 0, 0, false, notes);
    }
}
