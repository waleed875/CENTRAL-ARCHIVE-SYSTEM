package com.centralArchiveSystem.Archive.project.file.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Only safe, user-facing metadata. Never expose object keys, stored names or
 * checksums through the global search endpoint.
 */
public record GlobalFileSearchResult(
        UUID id,
        UUID projectId,
        String originalName,
        String extension,
        String mimeType,
        Long sizeBytes,
        LocalDateTime createdAt,
        UUID categoryId,
        String categoryName,
        String projectName,
        String projectNumber,
        Integer projectYear,
        UUID organizationId,
        String organizationName,
        Integer versionNo,
        Boolean isLatestVersion
) {}
