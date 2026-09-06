package com.centralArchiveSystem.Archive.dashboard.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Recent activity item on the dashboard")
public record ActivityResponse(
    @Schema(description = "Type of action performed", example = "PROJECT_CREATED") String action,
    @Schema(description = "User who performed the action") UserSummary user,
    @Schema(description = "Human-readable description of the activity") String description,
    @Schema(description = "Entity type affected", example = "PROJECT") String entityType,
    @Schema(description = "ID of the affected entity") UUID entityId,
    @Schema(description = "Timestamp when the activity occurred") LocalDateTime createdAt
) {
    @Schema(description = "User summary for activity")
    public record UserSummary(UUID id, String username, String fullName) {}
}
