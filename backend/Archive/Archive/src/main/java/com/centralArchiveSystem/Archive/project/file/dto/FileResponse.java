package com.centralArchiveSystem.Archive.project.file.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record FileResponse(
    UUID id,
    UUID projectId,
    UUID categoryId,
    String categoryCode,
    String categoryName,
    UUID typeId,
    String typeCode,
    String typeName,
    UUID confidentialityLevelId,
    String confidentialityLevelName,
    String originalName,
    String storedName,
    String bucketName,
    String objectKey,
    String extension,
    String mimeType,
    Long sizeBytes,
    String checksumSha256,
    String status,
    UUID uploadedBy,
    String uploaderName,
    String sourceType,
    String sourceReference,
    LocalDateTime deletedAt,
    UUID deletedBy,
    String deleteReason,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
