package com.centralArchiveSystem.Archive.core.organization.dto;

public record OrganizationUpdateRequest(
    String name,
    String description,
    Boolean isActive
) {}
