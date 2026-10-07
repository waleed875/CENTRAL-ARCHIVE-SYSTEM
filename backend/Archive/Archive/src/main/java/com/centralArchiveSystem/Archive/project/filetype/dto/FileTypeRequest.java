package com.centralArchiveSystem.Archive.project.filetype.dto;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record FileTypeRequest(
    @NotNull(message = "حقل فئة الملف مطلوب") UUID categoryId,
    @NotBlank(message = "حقل رمز نوع الملف مطلوب") @Size(max = 60, message = "تجاوز طول الحقل الحد المسموح") String code,
    @NotBlank(message = "حقل اسم نوع الملف مطلوب") @Size(max = 160, message = "تجاوز طول الحقل الحد المسموح") String name,
    @Size(max = 20, message = "تجاوز طول الحقل الحد المسموح") String defaultExtension,
    String description,
    Boolean isActive
) {}
