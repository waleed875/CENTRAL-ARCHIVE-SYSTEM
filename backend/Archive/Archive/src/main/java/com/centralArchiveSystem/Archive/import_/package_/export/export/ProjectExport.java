package com.centralArchiveSystem.Archive.import_.package_.export.export;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "import.export_packages")
@Schema(name = "import.export_packages")
public class ProjectExport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "package_uuid", nullable = false, unique = true)
    private String packageUuid;

    @Column(name = "package_name", nullable = false)
    private String packageName;

    @Column(name = "schema_version", nullable = false)
    private String schemaVersion;

    @Column(name = "source_organization_id")
    private UUID sourceOrganizationId;

    @Column(name = "source_username")
    private String sourceUsername;

    @Column(name = "projects_count", nullable = false)
    private Integer projectsCount;

    @Column(name = "files_count", nullable = false)
    private Integer filesCount;

    @Column(name = "package_hash")
    private String packageHash;

    @Column(name = "manifest_data", columnDefinition = "TEXT")
    private String manifestData;

    @Column(name = "status", nullable = false)
    private String status = "CREATED";

    @Column(name = "exported_by")
    private String exportedBy;

    @Column(name = "exported_at")
    private LocalDateTime exportedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public ProjectExport() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getPackageUuid() { return packageUuid; }
    public void setPackageUuid(String packageUuid) { this.packageUuid = packageUuid; }

    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }

    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }

    public UUID getSourceOrganizationId() { return sourceOrganizationId; }
    public void setSourceOrganizationId(UUID sourceOrganizationId) { this.sourceOrganizationId = sourceOrganizationId; }

    public String getSourceUsername() { return sourceUsername; }
    public void setSourceUsername(String sourceUsername) { this.sourceUsername = sourceUsername; }

    public Integer getProjectsCount() { return projectsCount; }
    public void setProjectsCount(Integer projectsCount) { this.projectsCount = projectsCount; }

    public Integer getFilesCount() { return filesCount; }
    public void setFilesCount(Integer filesCount) { this.filesCount = filesCount; }

    public String getPackageHash() { return packageHash; }
    public void setPackageHash(String packageHash) { this.packageHash = packageHash; }

    public String getManifestData() { return manifestData; }
    public void setManifestData(String manifestData) { this.manifestData = manifestData; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getExportedBy() { return exportedBy; }
    public void setExportedBy(String exportedBy) { this.exportedBy = exportedBy; }

    public LocalDateTime getExportedAt() { return exportedAt; }
    public void setExportedAt(LocalDateTime exportedAt) { this.exportedAt = exportedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
