package com.centralArchiveSystem.Archive.import_.package_.dto;

import com.centralArchiveSystem.Archive.import_.package_.BranchPackage;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Branch package response DTO")
public record PackageResponse(
    @Schema(description = "Package ID") UUID id,
    @Schema(description = "Package UUID") UUID packageUuid,
    @Schema(description = "Source organization ID") UUID sourceOrganizationId,
    @Schema(description = "Source terminal UUID") UUID sourceTerminalUuid,
    @Schema(description = "Source username") String sourceUsername,
    @Schema(description = "Package name") String packageName,
    @Schema(description = "Package hash (SHA-256)") String packageHash,
    @Schema(description = "Manifest hash (SHA-256)") String manifestHash,
    @Schema(description = "Schema version") String schemaVersion,
    @Schema(description = "Number of projects in package") Integer projectsCount,
    @Schema(description = "Number of files in package") Integer filesCount,
    @Schema(description = "Package status") BranchPackage.PackageStatus status,
    @Schema(description = "Received by central user ID") UUID receivedBy,
    @Schema(description = "Received timestamp") LocalDateTime receivedAt,
    @Schema(description = "Verified timestamp") LocalDateTime verifiedAt,
    @Schema(description = "Imported timestamp") LocalDateTime importedAt,
    @Schema(description = "Error message if failed") String errorMessage,
    @Schema(description = "Target organization ID") UUID targetOrganizationId,
    @Schema(description = "Creation timestamp") LocalDateTime createdAt,
    @Schema(description = "Last update timestamp") LocalDateTime updatedAt
) {
    public static PackageResponse fromEntity(BranchPackage pkg) {
        return new PackageResponse(
            pkg.getId(),
            pkg.getPackageUuid(),
            pkg.getSourceOrganizationId(),
            pkg.getSourceTerminalUuid(),
            pkg.getSourceUsername(),
            pkg.getPackageName(),
            pkg.getPackageHash(),
            pkg.getManifestHash(),
            pkg.getSchemaVersion(),
            pkg.getProjectsCount(),
            pkg.getFilesCount(),
            pkg.getStatus(),
            pkg.getReceivedBy(),
            pkg.getReceivedAt(),
            pkg.getVerifiedAt(),
            pkg.getImportedAt(),
            pkg.getErrorMessage(),
            pkg.getTargetOrganizationId(),
            pkg.getCreatedAt(),
            pkg.getUpdatedAt()
        );
    }
}
