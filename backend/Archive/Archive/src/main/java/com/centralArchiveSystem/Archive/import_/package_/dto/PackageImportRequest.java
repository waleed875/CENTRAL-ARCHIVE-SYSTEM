package com.centralArchiveSystem.Archive.import_.package_.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;
import java.util.UUID;

@Schema(description = "Package import request DTO")
public record PackageImportRequest(
    @JsonProperty("organization_mapping")
    @Schema(description = "Organization mapping: source organization ID -> central organization ID")
    Map<UUID, UUID> organizationMapping,

    @JsonProperty("confidentiality_level_id")
    @Schema(description = "Default confidentiality level when imported project/file metadata does not provide one")
    UUID confidentialityLevelId
) {}
