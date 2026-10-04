package com.centralArchiveSystem.Archive.project.project.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProjectCorrectionRequest(
        @JsonProperty("review_notes")
        @NotBlank(message = "حقل ملاحظات المراجعة مطلوب")
        @Size(max = 5000, message = "ملاحظات المراجعة يجب ألا تتجاوز 5000 حرف")
        String reviewNotes
) {}
