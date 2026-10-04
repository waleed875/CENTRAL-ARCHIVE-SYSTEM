package com.centralArchiveSystem.Archive.project.file.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record FileUploadRequest(
    @NotNull(message = "حقل فئة الملف مطلوب") UUID categoryId,
    UUID typeId,
    UUID confidentialityLevelId
) {}
