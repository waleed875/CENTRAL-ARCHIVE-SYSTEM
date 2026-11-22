package com.centralArchiveSystem.Archive.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateOwnProfileRequest(
        @NotBlank(message = "الاسم مطلوب")
        @Size(max = 200, message = "الاسم أطول من المسموح")
        String fullName) {}
