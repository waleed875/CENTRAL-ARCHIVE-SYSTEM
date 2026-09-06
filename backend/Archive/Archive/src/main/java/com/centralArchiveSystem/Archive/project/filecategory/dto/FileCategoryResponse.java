package com.centralArchiveSystem.Archive.project.filecategory.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record FileCategoryResponse(
    UUID id,
    String code,
    String name,
    String description,
    Boolean isActive,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    long filesCount
) {}
