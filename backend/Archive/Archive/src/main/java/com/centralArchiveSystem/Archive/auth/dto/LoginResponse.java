package com.centralArchiveSystem.Archive.auth.dto;

import java.util.List;
import java.util.UUID;

public record LoginResponse(
    String token,
    long expiresIn,
    UserInfo user
) {
    public record UserInfo(
        UUID id,
        String username,
        String fullName,
        String organization,
        List<String> roles,
        UUID maxConfidentialityLevelId
    ) {}
}
