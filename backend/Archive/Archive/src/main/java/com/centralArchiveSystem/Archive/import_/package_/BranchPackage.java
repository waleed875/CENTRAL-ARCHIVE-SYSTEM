package com.centralArchiveSystem.Archive.import_.package_;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "branch_packages", schema = "import")
@Schema(name = "import.branch_packages")
public class BranchPackage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "package_uuid", nullable = false, unique = true)
    private UUID packageUuid;

    @Column(name = "source_organization_id")
    private UUID sourceOrganizationId;

    @Column(name = "source_terminal_uuid", nullable = false)
    private UUID sourceTerminalUuid;

    @Column(name = "source_username")
    private String sourceUsername;

    @Column(name = "package_name", nullable = false)
    private String packageName;

    @Column(name = "package_hash", nullable = false, unique = true, length = 64)
    private String packageHash;

    @Column(name = "manifest_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    private String manifestHash;

    @Column(name = "schema_version", nullable = false)
    private String schemaVersion;

    @Column(name = "projects_count", nullable = false)
    private Integer projectsCount = 0;

    @Column(name = "files_count", nullable = false)
    private Integer filesCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PackageStatus status = PackageStatus.RECEIVED;

    @Column(name = "received_by")
    private UUID receivedBy;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "imported_at")
    private LocalDateTime importedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "manifest_data", columnDefinition = "TEXT")
    private String manifestData;

    @Column(name = "target_organization_id")
    private UUID targetOrganizationId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum PackageStatus {
        RECEIVED, VERIFIED, IMPORTED, FAILED, DUPLICATE
    }

    public BranchPackage() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getPackageUuid() { return packageUuid; }
    public void setPackageUuid(UUID packageUuid) { this.packageUuid = packageUuid; }

    public UUID getSourceOrganizationId() { return sourceOrganizationId; }
    public void setSourceOrganizationId(UUID sourceOrganizationId) { this.sourceOrganizationId = sourceOrganizationId; }

    public UUID getSourceTerminalUuid() { return sourceTerminalUuid; }
    public void setSourceTerminalUuid(UUID sourceTerminalUuid) { this.sourceTerminalUuid = sourceTerminalUuid; }

    public String getSourceUsername() { return sourceUsername; }
    public void setSourceUsername(String sourceUsername) { this.sourceUsername = sourceUsername; }

    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }

    public String getPackageHash() { return packageHash; }
    public void setPackageHash(String packageHash) { this.packageHash = packageHash; }

    public String getManifestHash() { return manifestHash; }
    public void setManifestHash(String manifestHash) { this.manifestHash = manifestHash; }

    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }

    public Integer getProjectsCount() { return projectsCount; }
    public void setProjectsCount(Integer projectsCount) { this.projectsCount = projectsCount; }

    public Integer getFilesCount() { return filesCount; }
    public void setFilesCount(Integer filesCount) { this.filesCount = filesCount; }

    public PackageStatus getStatus() { return status; }
    public void setStatus(PackageStatus status) { this.status = status; }

    public UUID getReceivedBy() { return receivedBy; }
    public void setReceivedBy(UUID receivedBy) { this.receivedBy = receivedBy; }

    public LocalDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(LocalDateTime receivedAt) { this.receivedAt = receivedAt; }

    public LocalDateTime getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(LocalDateTime verifiedAt) { this.verifiedAt = verifiedAt; }

    public LocalDateTime getImportedAt() { return importedAt; }
    public void setImportedAt(LocalDateTime importedAt) { this.importedAt = importedAt; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getManifestData() { return manifestData; }
    public void setManifestData(String manifestData) { this.manifestData = manifestData; }

    public UUID getTargetOrganizationId() { return targetOrganizationId; }
    public void setTargetOrganizationId(UUID targetOrganizationId) { this.targetOrganizationId = targetOrganizationId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
