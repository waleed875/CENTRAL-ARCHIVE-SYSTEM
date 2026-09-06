package com.centralArchiveSystem.Archive.import_.migration.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;

@Schema(description = "Configuration for running a migration batch")
public record MigrationRunRequest(
    @NotBlank(message = "Connection string is required")
    @Schema(description = "Database connection string", example = "jdbc:oracle:thin:@//old-server:1521/ARCHIVE") String connection_string,

    @Schema(description = "File path for data import (CSV/XML)", example = "/data/legacy_export.csv") String file_path,

    @NotBlank(message = "Type is required")
    @Schema(description = "Migration type", example = "ORACLE_TO_POSTGRES") String type,

    @Schema(description = "Column/field mapping between source and target", example = "{\"old_col\":\"new_col\"}") Map<String, String> type_mapping
) {}
