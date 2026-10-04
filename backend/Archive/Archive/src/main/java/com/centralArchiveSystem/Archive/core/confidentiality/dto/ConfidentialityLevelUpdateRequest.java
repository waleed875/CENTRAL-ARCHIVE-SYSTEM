package com.centralArchiveSystem.Archive.core.confidentiality.dto;

import jakarta.validation.constraints.Min;

public record ConfidentialityLevelUpdateRequest(
    String name,
    @Min(value = 1, message = "يجب ألا يقل ترتيب المستوى عن 1") Integer levelOrder,
    String description
) {}
