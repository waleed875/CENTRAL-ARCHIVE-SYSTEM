package com.centralArchiveSystem.Archive.core.confidentiality.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Request body for creating or updating a confidentiality level")
public record ConfidentialityLevelRequest(
    @NotBlank(message = "Code is required")
    @Schema(description = "Unique code for the confidentiality level", example = "TOP_SECRET")
    String code,

    @NotBlank(message = "Name is required")
    @Schema(description = "Display name of the confidentiality level", example = "Top Secret")
    String name,

    @NotNull(message = "Level order is required")
    @Min(value = 1, message = "Level order must be at least 1")
    @Schema(description = "Numeric ordering of the level (must be >= 1)", example = "1")
    Integer levelOrder,

    @Schema(description = "Optional description of the confidentiality level", example = "Highest level of confidentiality")
    String description
) {}
