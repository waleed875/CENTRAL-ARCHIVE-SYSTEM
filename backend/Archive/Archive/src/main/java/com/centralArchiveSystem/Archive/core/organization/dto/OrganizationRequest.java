package com.centralArchiveSystem.Archive.core.organization.dto;

import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public record OrganizationRequest(
    // Accept the documented spelling without breaking existing camelCase clients.
    @JsonAlias("parent_id") UUID parentId,
    @NotBlank(message = "حقل اسم المؤسسة مطلوب") @Size(max = 200, message = "تجاوز طول الحقل الحد المسموح") String name,
    @NotBlank(message = "حقل رمز المؤسسة مطلوب") @Size(max = 60, message = "تجاوز طول الحقل الحد المسموح") String code,
    @NotBlank(message = "حقل نوع المؤسسة مطلوب") String type,
    String description
) {}
