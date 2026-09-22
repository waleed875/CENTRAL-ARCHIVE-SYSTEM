package com.centralArchiveSystem.Archive.project.projecttype.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record ProjectTypeResponse(
    UUID id,
    String code,
    String name,
    String description,
    Boolean isActive,
    LocalDateTime createdAt,
    Long projectsCount
) {}
