package com.centralArchiveSystem.Archive.project.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MigrationDraftDiscardRequest(
        @NotBlank(message = "سبب استبعاد مشروع الترحيل مطلوب")
        @Size(min = 12, max = 1000, message = "سبب الاستبعاد يجب أن يكون بين 12 و1000 حرف")
        String reason) {}
