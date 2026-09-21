package com.centralArchiveSystem.Archive.import_.migration.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

@Schema(description = "Configuration for running a migration batch")
public record MigrationRunRequest(
    @NotNull
    @Valid
    @Schema(description = "Migration execution configuration")
    MigrationConfig migration_config
) {
    public record MigrationConfig(
        @NotBlank(message = "Connection string is required for the current staging migration adapter")
        @Schema(
            description = "Configured legacy staging JDBC URL. Dynamic source connections are not enabled in the current MVP.",
            example = "jdbc:postgresql://legacy-staging:5432/ARC2"
        )
        String connection_string,

        @Schema(description = "Reserved for a future file migration adapter; must be omitted in the current MVP")
        String file_path,

        @Schema(description = "Reserved for future project-type mapping adapters; must be empty in the current MVP")
        Map<String, UUID> project_type_mapping,

        @Schema(description = "Reserved for a future confidentiality override; must be omitted in the current MVP")
        UUID default_confidentiality_level_id
    ) {}
}
