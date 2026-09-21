package com.centralArchiveSystem.Archive.project.filecategory.dto;

public record FileCategoryUpdateRequest(
    String name,
    String description,
    Boolean isActive
) {}
