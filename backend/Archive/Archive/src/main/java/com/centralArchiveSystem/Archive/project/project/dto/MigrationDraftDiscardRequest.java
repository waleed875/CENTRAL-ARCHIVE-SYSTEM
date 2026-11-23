package com.centralArchiveSystem.Archive.project.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MigrationDraftDiscardRequest(
        @NotBlank(message = "سبب استبعاد مشروع الترحيل مطلوب")
        @Size(max = 1000, message = "سبب الاستبعاد يجب ألا يتجاوز 1000 حرف")
        String reason) {}
