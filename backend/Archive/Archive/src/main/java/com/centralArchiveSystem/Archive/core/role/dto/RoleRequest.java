package com.centralArchiveSystem.Archive.core.role.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Request body for creating or updating a role")
public record RoleRequest(
    @NotBlank(message = "حقل رمز الدور مطلوب")
    @Size(min = 1, max = 50, message = "رمز الدور يجب أن يكون بين 1 و 50 حرفاً")
    String code,

    @NotBlank(message = "حقل اسم الدور مطلوب")
    @Size(min = 1, max = 100, message = "اسم الدور يجب أن يكون بين 1 و 100 حرف")
    String name,

    @Schema(description = "Description of the role")
    String description
) {}
