package com.centralArchiveSystem.Archive.import_.migration.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;

@Schema(description = "Configuration for running a migration batch")
public record MigrationRunRequest(
    @NotBlank(message = "Connection string is required")
    @Schema(description = "Configured legacy staging JDBC URL. Dynamic source connections are not enabled in the current MVP.", example = "jdbc:postgresql://legacy-staging:5432/ARC2") String connection_string,

    @Schema(description = "Reserved for a future file migration adapter; must be omitted in the current MVP") String file_path,

    @NotBlank(message = "Type is required")
    @Schema(description = "Migration execution type; current supported value is LEGACY_DB", example = "LEGACY_DB") String type,

    @Schema(description = "Column/field mapping between source and target", example = "{\"old_col\":\"new_col\"}") Map<String, String> type_mapping
) {}
