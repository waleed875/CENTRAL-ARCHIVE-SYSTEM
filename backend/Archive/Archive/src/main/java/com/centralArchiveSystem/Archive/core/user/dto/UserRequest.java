package com.centralArchiveSystem.Archive.core.user.dto;

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

    @NotBlank(message = "حقل كلمة المرور مطلوب")
    @Size(min = 8, max = 100, message = "كلمة المرور يجب أن تكون بين 8 و 100 حرف")
    String password,

    @NotBlank(message = "حقل الاسم الكامل مطلوب")
    @Size(min = 1, max = 200, message = "الاسم الكامل يجب ألا يتجاوز 200 حرف")
    String fullName,

    @Schema(description = "User email address")
    String email,

    @Schema(description = "UUID of the organization this user belongs to")
    UUID organizationId,

    @Schema(description = "UUID of the maximum confidentiality level this user can access")
    UUID maxConfidentialityLevelId,

    @Schema(description = "Set of role UUIDs to assign to the user")
    Set<UUID> roleIds
) {}
