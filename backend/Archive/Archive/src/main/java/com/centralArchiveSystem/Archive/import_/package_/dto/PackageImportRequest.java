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

    @JsonProperty("project_type_mapping")
    @Schema(description = "Project type mapping: source project type ID -> central project type ID")
    Map<UUID, UUID> projectTypeMapping,

    @JsonProperty("confidentiality_mapping")
    @Schema(description = "Confidentiality mapping: source confidentiality ID -> central confidentiality ID")
    Map<UUID, UUID> confidentialityMapping,

    @JsonProperty("file_category_mapping")
    @Schema(description = "File category mapping: source category ID -> central category ID")
    Map<UUID, UUID> fileCategoryMapping,

    @JsonProperty("file_type_mapping")
    @Schema(description = "File type mapping: source file type ID -> central file type ID")
    Map<UUID, UUID> fileTypeMapping,

    @JsonProperty("confidentiality_level_id")
    @Schema(description = "Default central confidentiality level when source metadata has none")
    UUID confidentialityLevelId
) {
    public PackageImportRequest(Map<UUID, UUID> organizationMapping,
                                UUID confidentialityLevelId) {
        this(
                organizationMapping,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                confidentialityLevelId
        );
    }

    public PackageImportRequest {
        organizationMapping = organizationMapping != null ? Map.copyOf(organizationMapping) : Map.of();
        projectTypeMapping = projectTypeMapping != null ? Map.copyOf(projectTypeMapping) : Map.of();
        confidentialityMapping = confidentialityMapping != null ? Map.copyOf(confidentialityMapping) : Map.of();
        fileCategoryMapping = fileCategoryMapping != null ? Map.copyOf(fileCategoryMapping) : Map.of();
        fileTypeMapping = fileTypeMapping != null ? Map.copyOf(fileTypeMapping) : Map.of();
    }
}
