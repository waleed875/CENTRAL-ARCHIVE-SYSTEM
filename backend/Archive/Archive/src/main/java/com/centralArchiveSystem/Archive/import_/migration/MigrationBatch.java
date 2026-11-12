package com.centralArchiveSystem.Archive.import_.migration;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "migration_batches",
        schema = "import",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_migration_batches_source_batch_number",
                columnNames = {"migration_source_id", "batch_number"}
        )
)
@Schema(name = "import.migration_batches")
public class MigrationBatch {

    public enum Status {
        PLANNED, RUNNING, SUCCESS, FAILED, PARTIAL, CANCELLED
    }

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "migration_source_id", nullable = false)
    private UUID migrationSourceId;

    @Column(name = "batch_number", nullable = false)
    private String batchNumber;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PLANNED;

    @Column(name = "projects_count")
    private Integer projectsCount = 0;

    @Column(name = "files_count")
    private Integer filesCount = 0;

    @Column(name = "remaining_projects_count", nullable = false)
    private Integer remainingProjectsCount = 0;

    @Column(name = "remaining_files_count", nullable = false)
    private Integer remainingFilesCount = 0;

    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested = false;

    @Column(columnDefinition = "text")
    private String notes;

    public MigrationBatch() {}

    public MigrationBatch(UUID id, UUID migrationSourceId, String batchNumber, LocalDateTime startedAt, LocalDateTime completedAt, Status status, Integer projectsCount, Integer filesCount, String notes) {
        this.id = id;
        this.migrationSourceId = migrationSourceId;
        this.batchNumber = batchNumber;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.status = status;
        this.projectsCount = projectsCount;
        this.filesCount = filesCount;
        this.notes = notes;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getMigrationSourceId() { return migrationSourceId; }
    public void setMigrationSourceId(UUID migrationSourceId) { this.migrationSourceId = migrationSourceId; }

    public String getBatchNumber() { return batchNumber; }
    public void setBatchNumber(String batchNumber) { this.batchNumber = batchNumber; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public Integer getProjectsCount() { return projectsCount; }
    public void setProjectsCount(Integer projectsCount) { this.projectsCount = projectsCount; }

    public Integer getFilesCount() { return filesCount; }
    public void setFilesCount(Integer filesCount) { this.filesCount = filesCount; }

    public Integer getRemainingProjectsCount() { return remainingProjectsCount; }
    public void setRemainingProjectsCount(Integer remainingProjectsCount) { this.remainingProjectsCount = remainingProjectsCount; }

    public Integer getRemainingFilesCount() { return remainingFilesCount; }
    public void setRemainingFilesCount(Integer remainingFilesCount) { this.remainingFilesCount = remainingFilesCount; }

    public boolean isCancelRequested() { return cancelRequested; }
    public void setCancelRequested(boolean cancelRequested) { this.cancelRequested = cancelRequested; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
