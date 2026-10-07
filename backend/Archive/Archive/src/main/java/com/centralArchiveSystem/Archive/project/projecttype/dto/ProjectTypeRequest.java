package com.centralArchiveSystem.Archive.project.projecttype.dto;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;

public record ProjectTypeRequest(
    @NotBlank(message = "حقل رمز نوع المشروع مطلوب") @Size(max = 60, message = "تجاوز طول الحقل الحد المسموح") String code,
    @NotBlank(message = "حقل اسم نوع المشروع مطلوب") @Size(max = 160, message = "تجاوز طول الحقل الحد المسموح") String name,
    String description
) {}
