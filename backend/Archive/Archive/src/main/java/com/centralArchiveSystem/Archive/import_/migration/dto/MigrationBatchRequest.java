package com.centralArchiveSystem.Archive.import_.migration.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Request to create or update a migration batch")
public record MigrationBatchRequest(
    @Schema(description = "Notes for the batch") String notes,
    @Schema(description = "Expected number of projects") Integer projectsCount,
    @Schema(description = "Expected number of files") Integer filesCount
) {}
