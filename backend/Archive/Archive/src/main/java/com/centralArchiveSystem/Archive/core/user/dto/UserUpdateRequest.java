package com.centralArchiveSystem.Archive.core.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.centralArchiveSystem.Archive.core.user.User;

import java.util.UUID;

public record UserUpdateRequest(
    @Size(min = 3, max = 50, message = "اسم المستخدم يجب أن يكون بين 3 و50 حرفاً") @Pattern(regexp = "[A-Za-z0-9._-]+", message = "اسم المستخدم يقبل الأحرف اللاتينية والأرقام و._- فقط") String username,
    @Size(max = 200, message = "تجاوز طول الحقل الحد المسموح") @Pattern(regexp = "(?sU).*\\S.*", message = "لا يجوز أن يكون الحقل فارغاً") String fullName,
    @Size(max = 200, message = "تجاوز طول الحقل الحد المسموح") @Email(message = "صيغة البريد الإلكتروني غير صالحة") String email,
    UUID organizationId,
    UUID maxConfidentialityLevelId,
    User.UserStatus status,
    Boolean clearConfidentialityLevel
) {
    /** Keep existing request-construction tests and internal integrations source-compatible. */
    public UserUpdateRequest(String fullName, String email, UUID organizationId,
                             UUID maxConfidentialityLevelId, User.UserStatus status) {
        this(null, fullName, email, organizationId, maxConfidentialityLevelId, status, null);
    }
}
