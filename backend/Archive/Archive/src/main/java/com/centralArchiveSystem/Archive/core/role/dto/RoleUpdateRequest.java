package com.centralArchiveSystem.Archive.core.role.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
public record RoleUpdateRequest(
    @Size(max = 100, message = "تجاوز طول الحقل الحد المسموح") @Pattern(regexp = "(?sU).*\\S.*", message = "لا يجوز أن يكون الحقل فارغاً") String name,
    String description,
    Boolean isActive
) {}
