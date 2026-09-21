package com.centralArchiveSystem.Archive.project.member.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record MemberCreateRequest(
    @JsonProperty("person_name")
    @NotBlank(message = "person_name is required")
    String personName,
    @JsonProperty("organization_name") String organizationName,
    @JsonProperty("role_name") String roleName,
    String notes
) {}
