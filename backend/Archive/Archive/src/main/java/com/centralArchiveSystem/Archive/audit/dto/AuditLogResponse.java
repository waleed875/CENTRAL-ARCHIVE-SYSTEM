package com.centralArchiveSystem.Archive.audit.dto;

import com.centralArchiveSystem.Archive.audit.AuditLog;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Audit log response DTO")
public record AuditLogResponse(
    @Schema(description = "Audit log ID") UUID id,
    @Schema(description = "User ID who performed the action") UUID userId,
    @Schema(description = "Action performed") String action,
    @Schema(description = "Entity type affected") String entityType,
    @Schema(description = "Entity ID affected") UUID entityId,
    @Schema(description = "Description of the action") String description,
    @Schema(description = "Previous values (JSON)") String oldValues,
    @Schema(description = "New values (JSON)") String newValues,
    @Schema(description = "IP address of the user") String ipAddress,
    @Schema(description = "Timestamp of the action") LocalDateTime createdAt
) {
    public static AuditLogResponse fromEntity(AuditLog auditLog) {
        return new AuditLogResponse(
            auditLog.getId(),
            auditLog.getUserId(),
            auditLog.getAction(),
            auditLog.getEntityType(),
            auditLog.getEntityId(),
            auditLog.getDescription(),
            auditLog.getOldValues(),
            auditLog.getNewValues(),
            auditLog.getIpAddress(),
            auditLog.getCreatedAt()
        );
    }
}
