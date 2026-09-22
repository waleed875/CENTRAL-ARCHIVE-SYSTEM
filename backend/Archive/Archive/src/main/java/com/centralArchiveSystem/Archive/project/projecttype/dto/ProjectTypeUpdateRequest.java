package com.centralArchiveSystem.Archive.project.projecttype.dto;

public record ProjectTypeUpdateRequest(
    String name,
    String description,
    Boolean isActive
) {}
