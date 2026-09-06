package com.centralArchiveSystem.Archive.audit;

import com.centralArchiveSystem.Archive.audit.dto.AuditEntityHistoryResponse;
import com.centralArchiveSystem.Archive.audit.dto.AuditLogResponse;
import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import com.centralArchiveSystem.Archive.common.dto.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/audit-logs")
@Tag(name = "Audit Logs", description = "Audit log management endpoints")
@PreAuthorize("hasRole('system_admin')")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping
    @Operation(summary = "Search audit logs with filtering and pagination")
    public ResponseEntity<ApiResponse<PagedResponse<AuditLogResponse>>> search(
            @Parameter(description = "User ID") @RequestParam(required = false) UUID userId,
            @Parameter(description = "Action type") @RequestParam(required = false) String action,
            @Parameter(description = "Entity type") @RequestParam(required = false) String entityType,
            @Parameter(description = "Entity ID") @RequestParam(required = false) UUID entityId,
            @Parameter(description = "Start date") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @Parameter(description = "End date") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @Parameter(description = "Page number (1-based)") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size) {

        Page<AuditLogResponse> logs = auditLogService.search(
                userId, action, entityType, entityId, startDate, endDate, page - 1, size);
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.fromSpring(logs, page, size)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get audit log by ID")
    public ResponseEntity<ApiResponse<AuditLogResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(auditLogService.getById(id)));
    }

    @GetMapping("/entity/{type}/{id}")
    @Operation(summary = "Get entity history - all audit logs for a specific entity")
    public ResponseEntity<ApiResponse<AuditEntityHistoryResponse>> getEntityHistory(
            @Parameter(description = "Entity type") @PathVariable String type,
            @Parameter(description = "Entity ID") @PathVariable UUID id,
            @Parameter(description = "Page number (1-based)") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "50") int size) {

        AuditEntityHistoryResponse response = auditLogService.getEntityHistory(type, id, page - 1, size);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
