package com.centralArchiveSystem.Archive.import_.migration.dto;

import com.centralArchiveSystem.Archive.import_.migration.MigrationSourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Request to create or update a migration source")
public record MigrationSourceRequest(
    @NotBlank(message = "حقل اسم المصدر مطلوب")
    @Size(max = 255)
    @Schema(description = "Name of the migration source", example = "Legacy DB Office A") String name,

    @NotNull(message = "حقل نوع المصدر مطلوب")
    @Schema(description = "Type of source. See GET /api/v1/enums for the current list",
            example = "LEGACY_DB") MigrationSourceType sourceType,

    @Size(max = 255)
    @Schema(description = "Database name", example = "legacy_archives") String databaseName,

    @NotBlank(message = "حقل اسم المكتب مطلوب، إذ يحدّد المؤسسة وينطاق كل معرّف مُرحَّل")
    @Size(max = 255)
    @Schema(description = "Office name. Unique per organisation and part of every migrated project reference",
            example = "Central Office") String officeName,

    @Size(max = 200)
    @Schema(description = "Legacy database host", example = "10.0.0.5") String host,

    @Min(value = 1, message = "يجب أن يكون المنفذ بين 1 و 65535")
    @Max(value = 65535, message = "يجب أن يكون المنفذ بين 1 و 65535")
    @Schema(description = "Legacy database port", example = "5432") Integer port,

    @Size(max = 200)
    @Schema(description = "Legacy database user", example = "legacy_reader") String username,

    @Size(max = 200)
    @Schema(description = "Legacy database password. Stored encrypted and never returned by the API",
            example = "change-me") String password,

    @Schema(description = "Description of the migration source") String description
) {}
