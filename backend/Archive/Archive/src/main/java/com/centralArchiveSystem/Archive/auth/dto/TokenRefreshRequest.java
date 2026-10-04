package com.centralArchiveSystem.Archive.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record TokenRefreshRequest(
    @NotBlank(message = "رمز التحديث مطلوب") String token
) {}
