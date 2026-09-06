package com.centralArchiveSystem.Archive.core.organization.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record OrganizationResponse(
    UUID id,
    UUID parentId,
    String name,
    String code,
    String type,
    String description,
    Boolean isActive,
    LocalDateTime createdAt,
    long childrenCount,
    long projectsCount
) {}
