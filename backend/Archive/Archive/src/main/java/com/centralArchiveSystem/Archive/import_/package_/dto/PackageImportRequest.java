package com.centralArchiveSystem.Archive.import_.package_.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;
import java.util.UUID;

@Schema(description = "Package import request DTO")
public record PackageImportRequest(
    @Schema(description = "Target organization ID to import into") UUID targetOrganizationId,
    @Schema(description = "Organization ID mapping: source organization ID -> target organization ID") Map<UUID, UUID> organizationMapping,
    @Schema(description = "Whether to skip duplicate projects", defaultValue = "true") boolean skipDuplicates,
    @Schema(description = "Whether to overwrite existing files on conflict", defaultValue = "false") boolean overwriteExisting
) {}
