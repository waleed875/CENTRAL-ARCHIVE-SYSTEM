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
    @GetMapping("/{sourceId}/file-reconciliation")
    @PreAuthorize("hasRole('system_admin')")
    @Operation(summary = "Read-only paged verification of DONE migration file links and MinIO objects")
    public ResponseEntity<ApiResponse<MigrationFileAuditService.ReconciliationPage>> reconcile(
            @PathVariable UUID sourceId,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(service.reconcilePage(sourceId, after, limit)));
    }

    @GetMapping("/{sourceId}/orphan-candidates")
    @PreAuthorize("hasRole('system_admin')")
    @Operation(summary = "Read-only source/project-scoped MinIO orphan candidate inventory")
    public ResponseEntity<ApiResponse<MigrationFileAuditService.OrphanPage>> orphanCandidates(
            @PathVariable UUID sourceId,
            @RequestParam int legacyProjectId,
            @RequestParam(defaultValue = "files") String area,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(service.orphanCandidates(
                        sourceId, legacyProjectId, area, after, limit)));
    }

}
