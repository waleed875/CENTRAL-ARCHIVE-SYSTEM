package com.centralArchiveSystem.Archive.import_.package_.export.export;

import com.centralArchiveSystem.Archive.auth.CurrentUserService;
import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/export/packages")
@Tag(name = "Export Packages", description = "Export package generation endpoints")
public class ExportController {

    private final ExportService exportService;
    private final CurrentUserService currentUserService;

    public ExportController(ExportService exportService, CurrentUserService currentUserService) {
        this.exportService = exportService;
        this.currentUserService = currentUserService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('system_admin', 'export_officer')")
    @Operation(summary = "Create an export package from selected projects")
    public ResponseEntity<ApiResponse<ProjectExport>> create(@Valid @RequestBody ExportRequest request) {
        ProjectExport export = exportService.createExport(
                request.projectIds(), request.sourceOrganizationId(), currentUserService.username());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(export, "Export package created"));
    }

    @GetMapping
    @Operation(summary = "List export packages")
    public ResponseEntity<ApiResponse<List<ProjectExport>>> list(
            @Parameter(description = "Source organization ID filter")
            @RequestParam(required = false) UUID sourceOrganizationId) {
        return ResponseEntity.ok(ApiResponse.success(exportService.list(sourceOrganizationId)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get export package by ID")
    public ResponseEntity<ApiResponse<ProjectExport>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(exportService.getById(id)));
    }

    @GetMapping("/{id}/manifest")
    @PreAuthorize("hasAnyRole('system_admin', 'export_officer')")
    @Operation(summary = "Download the export package manifest (JSON)")
    public ResponseEntity<String> downloadManifest(@PathVariable UUID id) {
        ProjectExport export = exportService.getById(id);
        return ResponseEntity.ok(export.getManifestData());
    }
}
