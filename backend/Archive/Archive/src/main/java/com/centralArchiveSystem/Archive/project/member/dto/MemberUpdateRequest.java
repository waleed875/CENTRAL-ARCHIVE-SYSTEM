package com.centralArchiveSystem.Archive.project.member.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonProperty;

public record MemberUpdateRequest(
    @JsonProperty("person_name") @Size(max = 200, message = "تجاوز طول الحقل الحد المسموح") @Pattern(regexp = "(?sU).*\\S.*", message = "لا يجوز أن يكون الحقل فارغاً") String personName,
    @JsonProperty("organization_name") @Size(max = 200, message = "تجاوز طول الحقل الحد المسموح") String organizationName,
    @JsonProperty("role_name") @Size(max = 120, message = "تجاوز طول الحقل الحد المسموح") String roleName,
    String notes
) {}
