package com.centralArchiveSystem.Archive.project.member.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record MemberResponse(
    UUID id,
    UUID projectId,
    String personName,
    String organizationName,
    String roleName,
    String notes,
    LocalDateTime createdAt
) {}
