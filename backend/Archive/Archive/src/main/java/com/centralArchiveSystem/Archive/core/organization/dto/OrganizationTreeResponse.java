package com.centralArchiveSystem.Archive.core.organization.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record OrganizationTreeResponse(
    UUID id,
    UUID parentId,
    String name,
    String code,
    String type,
    String description,
    Boolean isActive,
    LocalDateTime createdAt,
    List<OrganizationTreeResponse> children
) {
    public OrganizationTreeResponse {
        if (children == null) children = new ArrayList<>();
    }
}
