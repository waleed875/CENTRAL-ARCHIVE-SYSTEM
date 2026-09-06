package com.centralArchiveSystem.Archive.core.role.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Request body for creating or updating a role")
public record RoleRequest(
    @NotBlank(message = "Role code is required")
    @Size(min = 1, max = 50, message = "Role code must be between 1 and 50 characters")
    String code,

    @NotBlank(message = "Role name is required")
    @Size(min = 1, max = 100, message = "Role name must be between 1 and 100 characters")
    String name,

    @Schema(description = "Description of the role")
    String description
) {}
