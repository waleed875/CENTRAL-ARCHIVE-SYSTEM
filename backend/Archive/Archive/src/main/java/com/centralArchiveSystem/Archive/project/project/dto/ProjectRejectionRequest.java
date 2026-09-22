package com.centralArchiveSystem.Archive.project.project.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProjectRejectionRequest(
        @JsonProperty("rejection_reason")
        @NotBlank(message = "rejection_reason is required")
        @Size(max = 5000, message = "rejection_reason must not exceed 5000 characters")
        String rejectionReason
) {}
