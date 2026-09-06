package com.centralArchiveSystem.Archive.core.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

@Schema(description = "Response body for a user")
public record UserResponse(
    UUID id,
    String username,
    String fullName,
    String email,
    String status,
    UUID organizationId,
    UUID maxConfidentialityLevelId,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    @Schema(description = "Nested organization details") OrganizationSummary organization,
    @Schema(description = "Set of roles assigned to the user") Set<RoleSummary> roles,
    @Schema(description = "Confidentiality level summary") ConfidentialityLevelSummary maxConfidentialityLevel
) {
    public record OrganizationSummary(UUID id, String name, String code) {}
    public record RoleSummary(UUID id, String name, String description) {}
    public record ConfidentialityLevelSummary(UUID id, String name, Integer level) {}
}
