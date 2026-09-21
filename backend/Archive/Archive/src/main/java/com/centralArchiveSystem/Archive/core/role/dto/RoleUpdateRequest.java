package com.centralArchiveSystem.Archive.core.role.dto;

public record RoleUpdateRequest(
    String name,
    String description,
    Boolean isActive
) {}
