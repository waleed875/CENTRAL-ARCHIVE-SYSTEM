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
        STARTED, SUCCESS, FAILED, PARTIAL
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

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public ImportOperation() {}

    public ImportOperation(UUID packageId, UUID importedBy) {
        this.packageId = packageId;
        this.importedBy = importedBy;
        this.status = Status.STARTED;
        this.startedAt = LocalDateTime.now();
    }

    public ImportOperation(UUID id, UUID packageId, UUID importedBy, Status status, String errorMessage, LocalDateTime startedAt, LocalDateTime completedAt) {
        this.id = id;
        this.packageId = packageId;
        this.importedBy = importedBy;
        this.status = status;
        this.errorMessage = errorMessage;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
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

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
