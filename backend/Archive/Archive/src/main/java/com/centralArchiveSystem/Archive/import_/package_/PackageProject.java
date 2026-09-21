package com.centralArchiveSystem.Archive.import_.package_;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "package_projects",
    schema = "import",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_package_projects_package_project",
        columnNames = {"package_id", "project_id"}
    )
)
@Schema(name = "import.package_projects")
public class PackageProject {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "package_id", nullable = false)
    private UUID packageId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "local_project_uuid", nullable = false)
    private UUID localProjectUuid;

    @Column(name = "source_project_number")
    private String sourceProjectNumber;

    @Column(name = "project_name")
    private String projectName;

    @Column(name = "project_year")
    private Integer projectYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "import_status", nullable = false)
    private ImportStatus importStatus;

    @Column(name = "import_error", columnDefinition = "TEXT")
    private String importError;

    @Column(name = "files_count")
    private Integer filesCount;

    @Column(name = "files_imported")
    private Integer filesImported;

    @Column(name = "files_failed")
    private Integer filesFailed;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public enum ImportStatus {
        IMPORTED, SKIPPED, FAILED, DUPLICATE
    }

    public PackageProject() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getPackageId() { return packageId; }
    public void setPackageId(UUID packageId) { this.packageId = packageId; }

    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }

    public UUID getLocalProjectUuid() { return localProjectUuid; }
    public void setLocalProjectUuid(UUID localProjectUuid) { this.localProjectUuid = localProjectUuid; }

    public String getSourceProjectNumber() { return sourceProjectNumber; }
    public void setSourceProjectNumber(String sourceProjectNumber) { this.sourceProjectNumber = sourceProjectNumber; }

    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }

    public Integer getProjectYear() { return projectYear; }
    public void setProjectYear(Integer projectYear) { this.projectYear = projectYear; }

    public ImportStatus getImportStatus() { return importStatus; }
    public void setImportStatus(ImportStatus importStatus) { this.importStatus = importStatus; }

    public String getImportError() { return importError; }
    public void setImportError(String importError) { this.importError = importError; }

    public Integer getFilesCount() { return filesCount; }
    public void setFilesCount(Integer filesCount) { this.filesCount = filesCount; }

    public Integer getFilesImported() { return filesImported; }
    public void setFilesImported(Integer filesImported) { this.filesImported = filesImported; }

    public Integer getFilesFailed() { return filesFailed; }
    public void setFilesFailed(Integer filesFailed) { this.filesFailed = filesFailed; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
