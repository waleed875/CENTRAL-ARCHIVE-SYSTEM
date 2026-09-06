package com.centralArchiveSystem.Archive.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record TokenRefreshRequest(
    @NotBlank(message = "Refresh token is required") String token
) {}
