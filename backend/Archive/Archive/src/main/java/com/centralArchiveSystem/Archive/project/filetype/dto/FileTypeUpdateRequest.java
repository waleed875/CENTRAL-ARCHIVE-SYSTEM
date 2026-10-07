package com.centralArchiveSystem.Archive.project.filetype.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
public record FileTypeUpdateRequest(
    @Size(max = 160, message = "تجاوز طول الحقل الحد المسموح") @Pattern(regexp = "(?sU).*\\S.*", message = "لا يجوز أن يكون الحقل فارغاً") String name,
    @Size(max = 20, message = "تجاوز طول الحقل الحد المسموح") String defaultExtension,
    String description,
    Boolean isActive
) {}
