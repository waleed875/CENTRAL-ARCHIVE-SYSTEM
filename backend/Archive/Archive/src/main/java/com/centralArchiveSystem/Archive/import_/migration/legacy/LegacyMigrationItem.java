package com.centralArchiveSystem.Archive.import_.migration.legacy;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Tracks a single legacy item (a project or a file) as it is migrated from the
 * legacy staging database into the new system. Enables resumability: on restart
 * we continue from PENDING/RUNNING items instead of re-migrating everything.
 *
 * <p>{@code sourceKey} is only unique within a migration source. The legacy system runs once per
 * organisation and every organisation numbers its rows from the same small sequence, so
 * {@code PROJECT-23} exists in many organisations and the source is what keeps them apart.
 */
@Entity
@Table(name = "legacy_migration_items", schema = "import",
        uniqueConstraints = @UniqueConstraint(name = "uk_legacy_item_source_kind_source",
                columnNames = {"migration_source_id", "kind", "source_key"}))
@Schema(name = "import.legacy_migration_items")
public class LegacyMigrationItem {

    public enum Kind { PROJECT, FILE }

    public enum Status { PENDING, RUNNING, DONE, SKIPPED, FAILED }

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private Kind kind;

    @Column(name = "source_key", nullable = false, length = 255)
    private String sourceKey;

    @Column(name = "migration_source_id", nullable = false, columnDefinition = "uuid")
    private UUID migrationSourceId;

    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "target_id")
    private UUID targetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "attempts")
    private Integer attempts = 0;

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public LegacyMigrationItem() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Kind getKind() { return kind; }
    public void setKind(Kind kind) { this.kind = kind; }

    public String getSourceKey() { return sourceKey; }
    public void setSourceKey(String sourceKey) { this.sourceKey = sourceKey; }

    public UUID getMigrationSourceId() { return migrationSourceId; }
    public void setMigrationSourceId(UUID migrationSourceId) { this.migrationSourceId = migrationSourceId; }

    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }

    public UUID getTargetId() { return targetId; }
    public void setTargetId(UUID targetId) { this.targetId = targetId; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer attempts) { this.attempts = attempts; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
