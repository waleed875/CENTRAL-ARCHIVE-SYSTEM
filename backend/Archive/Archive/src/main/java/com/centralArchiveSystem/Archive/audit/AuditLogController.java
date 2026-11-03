package com.centralArchiveSystem.Archive.audit;

import com.centralArchiveSystem.Archive.audit.dto.AuditEntityHistoryResponse;
import com.centralArchiveSystem.Archive.audit.dto.AuditLogResponse;
import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import com.centralArchiveSystem.Archive.common.dto.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
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
@PreAuthorize("hasRole('system_admin') and @auditAccessPolicy.canReadAudit()")
public class AuditLogController {
    private final AuditLogService auditLogService;
    public AuditLogController(AuditLogService auditLogService) { this.auditLogService = auditLogService; }

    @GetMapping
    @Operation(summary = "Search audit logs with filtering and pagination")
    public ResponseEntity<ApiResponse<PagedResponse<AuditLogResponse>>> search(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("تاريخ البداية يجب ألا يأتي بعد تاريخ النهاية");
        }
        int limit = Math.max(1, Math.min(size, 100));
        Page<AuditLogResponse> logs = auditLogService.search(userId, action, entityType, entityId,
                startDate, endDate, page - 1, limit);
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.fromSpring(logs, page, limit)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get audit log by ID")
    public ResponseEntity<ApiResponse<AuditLogResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(auditLogService.getById(id)));
    }

    @GetMapping("/entity/{type}/{id}")
    @Operation(summary = "Get paged entity history")
    public ResponseEntity<ApiResponse<AuditEntityHistoryResponse>> getEntityHistory(
            @PathVariable String type, @PathVariable UUID id,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.success(auditLogService.getEntityHistory(
                type, id, page - 1, Math.max(1, Math.min(size, 100)))));
    }
}
