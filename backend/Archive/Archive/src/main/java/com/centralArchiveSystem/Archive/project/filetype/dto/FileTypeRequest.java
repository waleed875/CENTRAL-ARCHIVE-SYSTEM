package com.centralArchiveSystem.Archive.project.filetype.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record FileTypeRequest(
    @NotNull(message = "حقل فئة الملف مطلوب") UUID categoryId,
    @NotBlank(message = "حقل رمز نوع الملف مطلوب") String code,
    @NotBlank(message = "حقل اسم نوع الملف مطلوب") String name,
    String defaultExtension,
    String description,
    Boolean isActive
) {}
