package com.centralArchiveSystem.Archive.import_.package_.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "Package verification response DTO")
public record PackageVerifyResponse(
    @Schema(description = "Package ID") UUID packageId,
    @Schema(description = "Package UUID") String packageUuid,
    @Schema(description = "Whether verification passed") boolean valid,
    @Schema(description = "Manifest hash matches") boolean manifestHashValid,
    @Schema(description = "Package integrity valid") boolean packageIntegrityValid,
    @Schema(description = "Schema version supported") boolean schemaVersionValid,
    @Schema(description = "Projects count in manifest") Integer manifestProjectsCount,
    @Schema(description = "Files count in manifest") Integer manifestFilesCount,
    @Schema(description = "Verification timestamp") LocalDateTime verifiedAt,
    @Schema(description = "List of validation errors") List<String> errors,
    @Schema(description = "List of validation warnings") List<String> warnings,
    @Schema(description = "Project details from manifest") List<ProjectManifestDetail> projects,
    @Schema(description = "Total size in bytes of package") Long totalSizeBytes
) {
    @Schema(description = "Project manifest detail")
    public record ProjectManifestDetail(
        @Schema(description = "Source project ID") String projectId,
        @Schema(description = "Project number") String projectNumber,
        @Schema(description = "Project name") String projectName,
        @Schema(description = "Project year") Integer projectYear,
        @Schema(description = "Files count for this project") Integer filesCount,
        @Schema(description = "Whether project already exists locally") boolean duplicate,
        @Schema(description = "Local project UUID if duplicate") UUID existingLocalProjectUuid
    ) {}
}
