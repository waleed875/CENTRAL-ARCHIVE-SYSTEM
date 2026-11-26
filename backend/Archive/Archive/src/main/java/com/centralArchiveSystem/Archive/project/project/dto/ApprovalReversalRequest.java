package com.centralArchiveSystem.Archive.project.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ApprovalReversalRequest(
        @NotBlank(message = "سبب التراجع عن الاعتماد مطلوب")
        @Size(max = 1000, message = "سبب التراجع يجب ألا يتجاوز 1000 حرف")
        String reason
) {}
