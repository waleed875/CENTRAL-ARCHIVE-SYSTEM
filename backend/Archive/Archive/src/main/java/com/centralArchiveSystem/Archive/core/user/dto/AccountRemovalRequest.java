package com.centralArchiveSystem.Archive.core.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AccountRemovalRequest(
        @NotBlank(message = "سبب حذف الحساب مطلوب")
        @Size(max = 1000, message = "سبب الحذف يجب ألا يتجاوز 1000 حرف")
        String reason) {}
