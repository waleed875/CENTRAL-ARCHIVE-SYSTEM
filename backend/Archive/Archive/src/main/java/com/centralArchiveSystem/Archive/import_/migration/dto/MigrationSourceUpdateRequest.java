package com.centralArchiveSystem.Archive.import_.migration.dto;

import jakarta.validation.constraints.Size;

public record MigrationSourceUpdateRequest(
    @Size(max = 255) String name,
    @Size(max = 50) String sourceType,
    @Size(max = 255) String databaseName,
    @Size(max = 255) String officeName,
    String description
) {}
