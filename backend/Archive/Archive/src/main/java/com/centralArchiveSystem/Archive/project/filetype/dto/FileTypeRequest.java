package com.centralArchiveSystem.Archive.project.filetype.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record FileTypeRequest(
    @NotNull(message = "Category ID is required") UUID categoryId,
    @NotBlank(message = "Type code is required") String code,
    @NotBlank(message = "Type name is required") String name,
    String defaultExtension,
    String description,
    Boolean isActive
) {}
