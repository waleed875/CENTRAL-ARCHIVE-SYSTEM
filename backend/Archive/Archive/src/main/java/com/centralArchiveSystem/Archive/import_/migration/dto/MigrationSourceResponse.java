package com.centralArchiveSystem.Archive.import_.migration.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Response for a migration source")
public record MigrationSourceResponse(
    @Schema(description = "Unique identifier") UUID id,
    @Schema(description = "Name of the migration source") String name,
    @Schema(description = "Type of source") String sourceType,
    @Schema(description = "Database name") String databaseName,
    @Schema(description = "Office name") String officeName,
    @Schema(description = "Description") String description,
    @Schema(description = "Number of migration batches") Long batchesCount,
    @Schema(description = "Creation timestamp") LocalDateTime createdAt
) {}
