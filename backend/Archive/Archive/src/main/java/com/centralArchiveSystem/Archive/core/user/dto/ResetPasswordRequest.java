package com.centralArchiveSystem.Archive.core.user.dto;

import com.centralArchiveSystem.Archive.common.validation.PasswordPolicy;
import jakarta.validation.constraints.NotBlank;

public record ResetPasswordRequest(@NotBlank(message = "كلمة المرور الجديدة مطلوبة") @PasswordPolicy String newPassword) {}
