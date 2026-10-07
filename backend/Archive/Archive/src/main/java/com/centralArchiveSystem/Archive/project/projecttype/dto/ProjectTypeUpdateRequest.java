package com.centralArchiveSystem.Archive.project.projecttype.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
public record ProjectTypeUpdateRequest(
    @Size(max = 160, message = "تجاوز طول الحقل الحد المسموح") @Pattern(regexp = "(?sU).*\\S.*", message = "لا يجوز أن يكون الحقل فارغاً") String name,
    String description,
    Boolean isActive
) {}
