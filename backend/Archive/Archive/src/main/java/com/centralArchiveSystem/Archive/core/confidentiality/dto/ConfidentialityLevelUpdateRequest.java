package com.centralArchiveSystem.Archive.core.confidentiality.dto;

import jakarta.validation.constraints.Min;

public record ConfidentialityLevelUpdateRequest(
    String name,
    @Min(value = 1, message = "Level order must be at least 1") Integer levelOrder,
    String description
) {}
