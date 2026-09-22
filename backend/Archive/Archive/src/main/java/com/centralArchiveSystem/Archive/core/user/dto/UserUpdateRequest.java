package com.centralArchiveSystem.Archive.core.user.dto;

import com.centralArchiveSystem.Archive.core.user.User;

import java.util.UUID;

public record UserUpdateRequest(
    String fullName,
    String email,
    UUID organizationId,
    UUID maxConfidentialityLevelId,
    User.UserStatus status
) {}
