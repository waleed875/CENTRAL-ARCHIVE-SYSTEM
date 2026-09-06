package com.centralArchiveSystem.Archive.core.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Set;
import java.util.UUID;

@Schema(description = "Request body for creating or updating a user")
public record UserRequest(
    @NotBlank(message = "Username is required")
    @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
    String username,

    @Size(min = 8, max = 100, message = "Password must be between 8 and 100 characters")
    String password,

    @NotBlank(message = "Full name is required")
    @Size(min = 1, max = 200, message = "Full name must be between 1 and 200 characters")
    String fullName,

    @Schema(description = "User email address")
    String email,

    @Schema(description = "UUID of the organization this user belongs to")
    UUID organizationId,

    @Schema(description = "UUID of the maximum confidentiality level this user can access")
    UUID maxConfidentialityLevelId,

    @Schema(description = "Set of role UUIDs to assign to the user")
    Set<UUID> roleIds
) {}
