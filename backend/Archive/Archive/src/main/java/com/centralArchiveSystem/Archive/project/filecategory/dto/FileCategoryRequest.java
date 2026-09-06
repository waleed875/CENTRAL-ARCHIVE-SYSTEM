package com.centralArchiveSystem.Archive.project.filecategory.dto;

import jakarta.validation.constraints.NotBlank;

public record FileCategoryRequest(
    @NotBlank(message = "Category code is required") String code,
    @NotBlank(message = "Category name is required") String name,
    String description,
    Boolean isActive
) {}
