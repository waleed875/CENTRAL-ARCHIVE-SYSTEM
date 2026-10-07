package com.centralArchiveSystem.Archive.core.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.centralArchiveSystem.Archive.core.user.User;

import java.util.UUID;

public record UserUpdateRequest(
    @Size(max = 200, message = "تجاوز طول الحقل الحد المسموح") @Pattern(regexp = "(?sU).*\\S.*", message = "لا يجوز أن يكون الحقل فارغاً") String fullName,
    @Size(max = 200, message = "تجاوز طول الحقل الحد المسموح") @Email(message = "صيغة البريد الإلكتروني غير صالحة") String email,
    UUID organizationId,
    UUID maxConfidentialityLevelId,
    User.UserStatus status
) {}
