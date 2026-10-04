package com.centralArchiveSystem.Archive.project.member.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record MemberCreateRequest(
    @JsonProperty("person_name")
    @NotBlank(message = "اسم عضو المشروع مطلوب")
    String personName,
    @JsonProperty("organization_name") String organizationName,
    @JsonProperty("role_name") String roleName,
    String notes
) {}
