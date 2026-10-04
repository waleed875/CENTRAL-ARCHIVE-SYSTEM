package com.centralArchiveSystem.Archive.project.project.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProjectRejectionRequest(
        @JsonProperty("rejection_reason")
        @NotBlank(message = "حقل سبب الرفض مطلوب")
        @Size(max = 5000, message = "سبب الرفض يجب ألا يتجاوز 5000 حرف")
        String rejectionReason
) {}
