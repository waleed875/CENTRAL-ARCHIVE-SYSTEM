package com.centralArchiveSystem.Archive.project.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminProjectDeleteRequest(
        @NotBlank(message = "سبب حذف المشروع مطلوب")
        @Size(max = 1000, message = "سبب الحذف يجب ألا يتجاوز 1000 حرف")
        String reason) {}
