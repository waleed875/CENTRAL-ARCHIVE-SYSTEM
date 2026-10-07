package com.centralArchiveSystem.Archive.project.member.dto;

import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record MemberCreateRequest(
    @JsonProperty("person_name")
    @NotBlank(message = "اسم عضو المشروع مطلوب")
    @Size(max = 200, message = "تجاوز طول الحقل الحد المسموح") String personName,
    @JsonProperty("organization_name") @Size(max = 200, message = "تجاوز طول الحقل الحد المسموح") String organizationName,
    @JsonProperty("role_name") @Size(max = 120, message = "تجاوز طول الحقل الحد المسموح") String roleName,
    String notes
) {}
