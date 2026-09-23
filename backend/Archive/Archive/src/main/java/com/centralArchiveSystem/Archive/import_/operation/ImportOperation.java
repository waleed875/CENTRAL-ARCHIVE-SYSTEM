package com.centralArchiveSystem.Archive.import_.operation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "import_operations", schema = "import")
@Schema(name = "import.import_operations")
public class ImportOperation {

    public enum Status {
        STARTED, SUCCESS, FAILED, PARTIAL, INTERRUPTED
    }

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "package_id", nullable = false)
    private UUID packageId;

    @Column(name = "imported_by")
    private UUID importedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.STARTED;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "last_heartbeat_at", nullable = false)
    private LocalDateTime lastHeartbeatAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "request_snapshot", columnDefinition = "text")
    private String requestSnapshot;

    @Column(name = "retry_of")
    private UUID retryOf;

    @Column(name = "projects_total", nullable = false)
    private int projectsTotal;

    @Column(name = "projects_imported", nullable = false)
    private int projectsImported;

    @Column(name = "projects_partial", nullable = false)
    private int projectsPartial;

    @Column(name = "projects_failed", nullable = false)
    private int projectsFailed;

    @Column(name = "projects_duplicate", nullable = false)
    private int projectsDuplicate;

    @Column(name = "projects_skipped", nullable = false)
    private int projectsSkipped;

    public ImportOperation() {}

    public ImportOperation(UUID packageId, UUID importedBy) {
        this.packageId = packageId;
        this.importedBy = importedBy;
        this.status = Status.STARTED;
        this.startedAt = LocalDateTime.now();
        this.lastHeartbeatAt = this.startedAt;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getPackageId() { return packageId; }
    public void setPackageId(UUID packageId) { this.packageId = packageId; }

    public UUID getImportedBy() { return importedBy; }
    public void setImportedBy(UUID importedBy) { this.importedBy = importedBy; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getLastHeartbeatAt() { return lastHeartbeatAt; }
    public void setLastHeartbeatAt(LocalDateTime lastHeartbeatAt) { this.lastHeartbeatAt = lastHeartbeatAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

    public String getRequestSnapshot() { return requestSnapshot; }
    public void setRequestSnapshot(String requestSnapshot) { this.requestSnapshot = requestSnapshot; }

    public UUID getRetryOf() { return retryOf; }
    public void setRetryOf(UUID retryOf) { this.retryOf = retryOf; }

    public int getProjectsTotal() { return projectsTotal; }
    public void setProjectsTotal(int projectsTotal) { this.projectsTotal = projectsTotal; }

    public int getProjectsImported() { return projectsImported; }
    public void setProjectsImported(int projectsImported) { this.projectsImported = projectsImported; }

    public int getProjectsPartial() { return projectsPartial; }
    public void setProjectsPartial(int projectsPartial) { this.projectsPartial = projectsPartial; }

    public int getProjectsFailed() { return projectsFailed; }
    public void setProjectsFailed(int projectsFailed) { this.projectsFailed = projectsFailed; }

    public int getProjectsDuplicate() { return projectsDuplicate; }
    public void setProjectsDuplicate(int projectsDuplicate) { this.projectsDuplicate = projectsDuplicate; }

    public int getProjectsSkipped() { return projectsSkipped; }
    public void setProjectsSkipped(int projectsSkipped) { this.projectsSkipped = projectsSkipped; }
}
