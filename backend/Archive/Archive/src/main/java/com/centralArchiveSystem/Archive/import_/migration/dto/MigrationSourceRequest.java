package com.centralArchiveSystem.Archive.import_.migration.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Request to create or update a migration source")
public record MigrationSourceRequest(
    @NotBlank(message = "Name is required")
    @Size(max = 255)
    @Schema(description = "Name of the migration source", example = "Legacy DB Office A") String name,

    @NotBlank(message = "Source type is required")
    @Size(max = 50)
    @Schema(description = "Type of source", example = "ORACLE") String sourceType,

    @Size(max = 255)
    @Schema(description = "Database name", example = "legacy_archives") String databaseName,

    @Size(max = 255)
    @Schema(description = "Office name", example = "Central Office") String officeName,

    @Schema(description = "Description of the migration source") String description
) {}
