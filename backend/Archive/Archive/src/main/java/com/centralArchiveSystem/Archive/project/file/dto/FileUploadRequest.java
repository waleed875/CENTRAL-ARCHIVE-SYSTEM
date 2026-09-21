package com.centralArchiveSystem.Archive.project.file.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record FileUploadRequest(
    @NotNull(message = "Category ID is required") UUID categoryId,
    UUID typeId,
    UUID confidentialityLevelId,
    String sourceType,
    String sourceReference
) {}
