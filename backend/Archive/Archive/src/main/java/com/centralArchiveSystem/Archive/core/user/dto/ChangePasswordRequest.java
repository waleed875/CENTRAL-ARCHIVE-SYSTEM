package com.centralArchiveSystem.Archive.core.user.dto;

import com.centralArchiveSystem.Archive.common.validation.PasswordPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request body for changing a user's password")
public record ChangePasswordRequest(
    @NotBlank(message = "حقل كلمة المرور الحالية مطلوب") String currentPassword,
    @NotBlank(message = "حقل كلمة المرور الجديدة مطلوب") @PasswordPolicy String newPassword
) {}
