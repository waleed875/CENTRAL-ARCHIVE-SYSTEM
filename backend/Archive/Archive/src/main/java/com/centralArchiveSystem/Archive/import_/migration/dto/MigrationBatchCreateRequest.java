package com.centralArchiveSystem.Archive.import_.migration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MigrationBatchCreateRequest(
    @NotBlank(message = "حقل رقم الدفعة مطلوب")
    @Size(max = 80, message = "رقم الدفعة يجب ألا يتجاوز 80 حرفاً")
    String batchNumber,
    String notes
) {}
