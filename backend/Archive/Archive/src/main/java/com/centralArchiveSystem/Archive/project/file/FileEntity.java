package com.centralArchiveSystem.Archive.project.file;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "files", schema = "project")
public class FileEntity {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @Column(name = "type_id")
    private UUID typeId;

    @Column(name = "confidentiality_level_id")
    private UUID confidentialityLevelId;

    @Column(name = "original_name", nullable = false)
    private String originalName;

    @Column(name = "stored_name", nullable = false)
    private String storedName;

    @Column(name = "bucket_name", nullable = false)
    private String bucketName;

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Column(name = "extension")
    private String extension;

    @Column(name = "mime_type")
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "checksum_sha256", length = 64)
    private String checksumSha256;

    @Column(name = "status", nullable = false)
    private String status = "ACTIVE";

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    @Column(name = "source_type")
    private String sourceType;

    @Column(name = "source_reference")
    private String sourceReference;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "deleted_by")
    private UUID deletedBy;

    @Column(name = "delete_reason")
    private String deleteReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public FileEntity() {}

    public FileEntity(UUID id, UUID projectId, UUID categoryId, UUID typeId, UUID confidentialityLevelId, String originalName, String storedName, String bucketName, String objectKey, String extension, String mimeType, Long sizeBytes, String checksumSha256, String status, UUID uploadedBy, String sourceType, String sourceReference, LocalDateTime deletedAt, UUID deletedBy, String deleteReason, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.projectId = projectId;
        this.categoryId = categoryId;
        this.typeId = typeId;
        this.confidentialityLevelId = confidentialityLevelId;
        this.originalName = originalName;
        this.storedName = storedName;
        this.bucketName = bucketName;
        this.objectKey = objectKey;
        this.extension = extension;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.checksumSha256 = checksumSha256;
        this.status = status;
        this.uploadedBy = uploadedBy;
        this.sourceType = sourceType;
        this.sourceReference = sourceReference;
        this.deletedAt = deletedAt;
        this.deletedBy = deletedBy;
        this.deleteReason = deleteReason;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }

    public UUID getCategoryId() { return categoryId; }
    public void setCategoryId(UUID categoryId) { this.categoryId = categoryId; }

    public UUID getTypeId() { return typeId; }
    public void setTypeId(UUID typeId) { this.typeId = typeId; }

    public UUID getConfidentialityLevelId() { return confidentialityLevelId; }
    public void setConfidentialityLevelId(UUID confidentialityLevelId) { this.confidentialityLevelId = confidentialityLevelId; }

    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }

    public String getStoredName() { return storedName; }
    public void setStoredName(String storedName) { this.storedName = storedName; }

    public String getBucketName() { return bucketName; }
    public void setBucketName(String bucketName) { this.bucketName = bucketName; }

    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }

    public String getExtension() { return extension; }
    public void setExtension(String extension) { this.extension = extension; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }

    public String getChecksumSha256() { return checksumSha256; }
    public void setChecksumSha256(String checksumSha256) { this.checksumSha256 = checksumSha256; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public UUID getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(UUID uploadedBy) { this.uploadedBy = uploadedBy; }

    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }

    public String getSourceReference() { return sourceReference; }
    public void setSourceReference(String sourceReference) { this.sourceReference = sourceReference; }

    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }

    public UUID getDeletedBy() { return deletedBy; }
    public void setDeletedBy(UUID deletedBy) { this.deletedBy = deletedBy; }

    public String getDeleteReason() { return deleteReason; }
    public void setDeleteReason(String deleteReason) { this.deleteReason = deleteReason; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
