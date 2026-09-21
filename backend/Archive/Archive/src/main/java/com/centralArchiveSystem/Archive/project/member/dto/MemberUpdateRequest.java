package com.centralArchiveSystem.Archive.project.member.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MemberUpdateRequest(
    @JsonProperty("person_name") String personName,
    @JsonProperty("organization_name") String organizationName,
    @JsonProperty("role_name") String roleName,
    String notes
) {}
