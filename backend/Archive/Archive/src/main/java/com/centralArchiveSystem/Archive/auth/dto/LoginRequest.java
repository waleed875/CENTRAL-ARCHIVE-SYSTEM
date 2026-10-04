package com.centralArchiveSystem.Archive.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
    @NotBlank(message = "حقل اسم المستخدم مطلوب") String username,
    @NotBlank(message = "حقل كلمة المرور مطلوب") String password
) {}
