package com.centralArchiveSystem.Archive.project.filecategory.dto;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;

public record FileCategoryRequest(
    @NotBlank(message = "حقل رمز فئة الملف مطلوب") @Size(max = 60, message = "تجاوز طول الحقل الحد المسموح") String code,
    @NotBlank(message = "حقل اسم فئة الملف مطلوب") @Size(max = 160, message = "تجاوز طول الحقل الحد المسموح") String name,
    String description,
    Boolean isActive
) {}
