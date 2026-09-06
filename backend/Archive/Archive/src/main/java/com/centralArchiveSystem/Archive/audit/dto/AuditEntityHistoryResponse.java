package com.centralArchiveSystem.Archive.audit.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Entity history response containing all audit logs for a specific entity")
public record AuditEntityHistoryResponse(
    @Schema(description = "Entity type") String entityType,
    @Schema(description = "Entity ID") String entityId,
    @Schema(description = "Total number of audit entries") long totalEntries,
    @Schema(description = "List of audit log entries in chronological order") List<AuditLogResponse> entries
) {}
