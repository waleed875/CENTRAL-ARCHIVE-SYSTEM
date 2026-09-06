package com.centralArchiveSystem.Archive.core.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Request body for changing a user's password")
public record ChangePasswordRequest(
    @NotBlank(message = "Current password is required") String currentPassword,
    @NotBlank(message = "New password is required")
    @Size(min = 8, max = 100, message = "New password must be between 8 and 100 characters") String newPassword
) {}
