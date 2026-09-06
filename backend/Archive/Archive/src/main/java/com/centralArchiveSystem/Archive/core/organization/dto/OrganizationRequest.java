package com.centralArchiveSystem.Archive.core.organization.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record OrganizationRequest(
    UUID parentId,
    @NotBlank(message = "Organization name is required") String name,
    @NotBlank(message = "Organization code is required") String code,
    @NotBlank(message = "Organization type is required") String type,
    String description
) {}
