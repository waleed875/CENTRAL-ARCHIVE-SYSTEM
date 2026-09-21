package com.centralArchiveSystem.Archive.import_.migration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MigrationBatchCreateRequest(
    @NotBlank(message = "Batch number is required")
    @Size(max = 80, message = "Batch number must not exceed 80 characters")
    String batchNumber,
    String notes
) {}
