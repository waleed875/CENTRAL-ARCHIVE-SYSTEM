package com.centralArchiveSystem.Archive.import_.migration.legacy;

import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Does not mutate the migration source, file rows, MinIO objects, or retry state. */
@RestController
@RequestMapping("/api/v1/migration-sources")
public class MigrationFileAuditController {
    private final MigrationFileAuditService service;

    public MigrationFileAuditController(MigrationFileAuditService service) {
        this.service = service;
    }

    @GetMapping("/{sourceId}/file-audit")
    @PreAuthorize("hasRole('system_admin')")
    @Operation(summary = "Read-only file migration link audit and bounded MinIO storage sampling")
    public ResponseEntity<ApiResponse<MigrationFileAuditService.Report>> audit(
            @PathVariable UUID sourceId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(service.audit(sourceId)));
    }
}
