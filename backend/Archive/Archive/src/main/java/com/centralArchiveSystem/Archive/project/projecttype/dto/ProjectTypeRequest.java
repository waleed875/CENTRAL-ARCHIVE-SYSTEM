package com.centralArchiveSystem.Archive.project.projecttype.dto;

import jakarta.validation.constraints.NotBlank;

public record ProjectTypeRequest(
    @NotBlank(message = "Code is required") String code,
    @NotBlank(message = "Name is required") String name,
    String description
) {}
