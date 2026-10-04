package com.centralArchiveSystem.Archive.core.organization.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record OrganizationRequest(
    UUID parentId,
    @NotBlank(message = "حقل اسم المؤسسة مطلوب") String name,
    @NotBlank(message = "حقل رمز المؤسسة مطلوب") String code,
    @NotBlank(message = "حقل نوع المؤسسة مطلوب") String type,
    String description
) {}
