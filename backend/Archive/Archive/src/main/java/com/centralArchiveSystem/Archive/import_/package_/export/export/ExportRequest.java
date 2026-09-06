package com.centralArchiveSystem.Archive.import_.package_.export.export;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

@Schema(description = "Request DTO for exporting a package")
public record ExportRequest(
    @Schema(description = "Project IDs to include in the export", required = true)
    @NotEmpty(message = "At least one project is required") List<UUID> projectIds,
    @Schema(description = "Source organization ID") UUID sourceOrganizationId,
    @Schema(description = "Username of the exporter") String exportedBy
) {}
