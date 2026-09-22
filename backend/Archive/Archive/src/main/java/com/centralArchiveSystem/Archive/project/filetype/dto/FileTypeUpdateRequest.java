package com.centralArchiveSystem.Archive.project.filetype.dto;

public record FileTypeUpdateRequest(
    String name,
    String defaultExtension,
    String description,
    Boolean isActive
) {}
