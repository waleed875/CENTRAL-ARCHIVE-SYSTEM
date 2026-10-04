package com.centralArchiveSystem.Archive.project.projecttype.dto;

import jakarta.validation.constraints.NotBlank;

public record ProjectTypeRequest(
    @NotBlank(message = "حقل رمز نوع المشروع مطلوب") String code,
    @NotBlank(message = "حقل اسم نوع المشروع مطلوب") String name,
    String description
) {}
