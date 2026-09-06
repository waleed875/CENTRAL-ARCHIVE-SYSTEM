package com.centralArchiveSystem.Archive.project.member.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record MemberRequest(
    UUID projectId,
    @NotBlank(message = "Person name is required") String personName,
    String organizationName,
    String roleName,
    String notes
) {}
