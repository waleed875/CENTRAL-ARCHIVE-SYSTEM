package com.centralArchiveSystem.Archive.project.project.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProjectCorrectionRequest(
        @JsonProperty("review_notes")
        @NotBlank(message = "review_notes is required")
        @Size(max = 5000, message = "review_notes must not exceed 5000 characters")
        String reviewNotes
) {}
