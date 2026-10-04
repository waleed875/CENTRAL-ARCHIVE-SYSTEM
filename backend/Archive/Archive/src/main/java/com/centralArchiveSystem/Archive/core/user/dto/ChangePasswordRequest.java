package com.centralArchiveSystem.Archive.core.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Request body for changing a user's password")
public record ChangePasswordRequest(
    @NotBlank(message = "حقل كلمة المرور الحالية مطلوب") String currentPassword,
    @NotBlank(message = "حقل كلمة المرور الجديدة مطلوب")
    @Size(min = 8, max = 100, message = "كلمة المرور الجديدة يجب أن تكون بين 8 و 100 حرف") String newPassword
) {}
