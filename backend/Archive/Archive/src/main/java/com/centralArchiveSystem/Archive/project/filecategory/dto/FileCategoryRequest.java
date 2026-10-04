package com.centralArchiveSystem.Archive.project.filecategory.dto;

import jakarta.validation.constraints.NotBlank;

public record FileCategoryRequest(
    @NotBlank(message = "حقل رمز فئة الملف مطلوب") String code,
    @NotBlank(message = "حقل اسم فئة الملف مطلوب") String name,
    String description,
    Boolean isActive
) {}
