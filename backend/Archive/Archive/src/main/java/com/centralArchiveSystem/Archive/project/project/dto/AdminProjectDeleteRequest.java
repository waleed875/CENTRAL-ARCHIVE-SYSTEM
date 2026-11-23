package com.centralArchiveSystem.Archive.project.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminProjectDeleteRequest(
        @NotBlank(message = "سبب حذف المشروع مطلوب")
        @Size(min = 12, max = 1000, message = "سبب الحذف يجب أن يكون بين 12 و1000 حرف")
        String reason) {}
