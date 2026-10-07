package com.centralArchiveSystem.Archive.core.confidentiality.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;

public record ConfidentialityLevelUpdateRequest(
    @Size(max = 120, message = "تجاوز طول الحقل الحد المسموح") @Pattern(regexp = "(?sU).*\\S.*", message = "لا يجوز أن يكون الحقل فارغاً") String name,
    @Min(value = 1, message = "يجب ألا يقل ترتيب المستوى عن 1") Integer levelOrder,
    String description
) {}
