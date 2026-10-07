package com.centralArchiveSystem.Archive.core.organization.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonAlias;

public record OrganizationUpdateRequest(
    @Size(max = 200, message = "تجاوز طول الحقل الحد المسموح") @Pattern(regexp = "(?sU).*\\S.*", message = "لا يجوز أن يكون الحقل فارغاً") String name,
    String description,
    @JsonAlias("is_active") Boolean isActive
) {}
