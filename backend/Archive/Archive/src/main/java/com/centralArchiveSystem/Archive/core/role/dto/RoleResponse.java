package com.centralArchiveSystem.Archive.core.role.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Response body for a role")
public record RoleResponse(
    UUID id,
    String code,
    String name,
    String description,
    Boolean isActive,
    LocalDateTime createdAt,
    @Schema(description = "Number of users assigned to this role") Long usersCount
) {}
