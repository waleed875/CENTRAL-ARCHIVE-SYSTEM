package com.centralArchiveSystem.Archive.core.user.dto;

import com.centralArchiveSystem.Archive.common.validation.PasswordPolicy;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Email;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;

@Schema(description = "Request body for creating or updating a user")
public record UserRequest(
    @NotBlank(message = "حقل اسم المستخدم مطلوب")
    @Size(min = 3, max = 50, message = "اسم المستخدم يجب أن يكون بين 3 و 50 حرفاً")
    String username,
    @NotBlank(message = "حقل كلمة المرور مطلوب") @PasswordPolicy String password,
    @Size(max = 200, message = "الاسم الكامل يجب ألا يتجاوز 200 حرف") String fullName,
    @Schema(description = "User email address")
    @Email(message = "صيغة البريد الإلكتروني غير صالحة")
    @Size(max = 200, message = "تجاوز طول الحقل الحد المسموح") String email,
    @Schema(description = "UUID of the organization this user belongs to") UUID organizationId,
    @Schema(description = "UUID of the maximum confidentiality level this user can access") UUID maxConfidentialityLevelId,
    @Schema(description = "Set of role UUIDs to assign to the user")
    Set<@NotNull(message = "لا تُقبل عناصر فارغة داخل القائمة") UUID> roleIds
) {}
