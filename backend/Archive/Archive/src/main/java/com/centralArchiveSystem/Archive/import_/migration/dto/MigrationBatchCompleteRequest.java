package com.centralArchiveSystem.Archive.import_.migration.dto;

import com.centralArchiveSystem.Archive.import_.migration.MigrationBatch;
import jakarta.validation.constraints.NotNull;

public record MigrationBatchCompleteRequest(
    @NotNull(message = "حقل الحالة مطلوب")
    MigrationBatch.Status status,
    String notes
) {}
