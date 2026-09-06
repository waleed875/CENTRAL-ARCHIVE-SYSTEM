package com.centralArchiveSystem.Archive.project.filetype.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record FileTypeResponse(
    UUID id,
    UUID categoryId,
    String categoryCode,
    String categoryName,
    String code,
    String name,
    String defaultExtension,
    String description,
    Boolean isActive,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    long filesCount
) {}
