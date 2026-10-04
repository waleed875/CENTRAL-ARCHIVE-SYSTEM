package com.centralArchiveSystem.Archive.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record ChangePasswordRequest(
    @NotBlank(message = "حقل كلمة المرور الحالية مطلوب") String currentPassword,
    @NotBlank(message = "حقل كلمة المرور الجديدة مطلوب") String newPassword
) {}
