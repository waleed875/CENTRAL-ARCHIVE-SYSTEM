package com.centralArchiveSystem.Archive.auth.dto;

import com.centralArchiveSystem.Archive.common.validation.PasswordPolicy;
import jakarta.validation.constraints.NotBlank;

public record ChangePasswordRequest(
    @NotBlank(message = "حقل كلمة المرور الحالية مطلوب") String currentPassword,
    @NotBlank(message = "حقل كلمة المرور الجديدة مطلوب") @PasswordPolicy String newPassword
) {}
