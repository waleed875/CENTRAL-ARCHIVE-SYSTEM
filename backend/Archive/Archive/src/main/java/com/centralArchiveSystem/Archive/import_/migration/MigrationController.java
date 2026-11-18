package com.centralArchiveSystem.Archive.import_.migration;

import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import com.centralArchiveSystem.Archive.import_.migration.dto.*;
import com.centralArchiveSystem.Archive.import_.migration.legacy.MigrationProgressTracker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Migration", description = "Import / Migration management")
public class MigrationController {

    private final MigrationService migrationService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MigrationProgressTracker progressTracker;

    public MigrationController(MigrationService migrationService) {
        this.migrationService = migrationService;
    }

    @GetMapping("/migration-sources")
    @Operation(summary = "List all migration sources")
    public ResponseEntity<ApiResponse<List<MigrationSourceResponse>>> listSources() {
        return ResponseEntity.ok(ApiResponse.success(migrationService.listSources()));
    }

    @PostMapping("/migration-sources")
    @PreAuthorize("hasRole('system_admin')")
    @Operation(summary = "Create a migration source")
    public ResponseEntity<ApiResponse<MigrationSourceResponse>> createSource(
            @Valid @RequestBody MigrationSourceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(migrationService.createSource(request), "Migration source created"));
    }

    @GetMapping("/migration-sources/{id}")
    @Operation(summary = "Get a migration source by ID")
    public ResponseEntity<ApiResponse<MigrationSourceResponse>> getSourceById(
            @Parameter(description = "Source UUID") @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(migrationService.getSourceById(id)));
    }

    @PostMapping("/migration-sources/{id}/test-connection")
    @PreAuthorize("hasRole('system_admin')")
    @Operation(summary = "Test the configured legacy database connection without running a migration")
    public ResponseEntity<ApiResponse<Map<String, String>>> testSourceConnection(
            @Parameter(description = "Source UUID") @PathVariable UUID id) {
        String message = migrationService.testSourceConnection(id);
        return ResponseEntity.ok(ApiResponse.success(Map.of("message", message), "Migration source connection successful"));
    }

    @PatchMapping("/migration-sources/{id}")
    @PreAuthorize("hasRole('system_admin')")
    @Operation(summary = "Update a migration source")
    public ResponseEntity<ApiResponse<MigrationSourceResponse>> updateSource(
            @Parameter(description = "Source UUID") @PathVariable UUID id,
            @Valid @RequestBody MigrationSourceUpdateRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success(migrationService.updateSource(id, request), "Migration source updated"));
    }

    @DeleteMapping("/migration-sources/{id}")
    @PreAuthorize("hasRole('system_admin')")
    @Operation(summary = "Delete a migration source")
    public ResponseEntity<ApiResponse<Void>> deleteSource(
            @Parameter(description = "Source UUID") @PathVariable UUID id) {
        migrationService.deleteSource(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Migration source deleted"));
    }

    @GetMapping("/migration-sources/{id}/batches")
    @Operation(summary = "List batches for a migration source")
    public ResponseEntity<ApiResponse<List<MigrationBatchResponse>>> listBatches(
            @Parameter(description = "Source UUID") @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(migrationService.listBatches(id)));
    }

    @PostMapping("/migration-sources/{id}/batches")
    @PreAuthorize("hasRole('system_admin')")
    @Operation(summary = "Create a batch for a migration source")
    public ResponseEntity<ApiResponse<MigrationBatchResponse>> createBatch(
            @Parameter(description = "Source UUID") @PathVariable UUID id,
            @Valid @RequestBody MigrationBatchCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(migrationService.createBatch(id, request), "Migration batch created"));
    }

    @GetMapping("/migration-batches/{id}")
    @Operation(summary = "Get a migration batch by ID")
    public ResponseEntity<ApiResponse<MigrationBatchResponse>> getBatchById(
            @Parameter(description = "Batch UUID") @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(migrationService.getBatchById(id)));
    }

    @PatchMapping("/migration-batches/{id}")
    @PreAuthorize("hasRole('system_admin')")
    @Operation(summary = "Update a migration batch")
    public ResponseEntity<ApiResponse<MigrationBatchResponse>> updateBatch(
            @Parameter(description = "Batch UUID") @PathVariable UUID id,
            @Valid @RequestBody MigrationBatchUpdateRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success(migrationService.updateBatch(id, request), "Migration batch updated"));
    }

    @GetMapping("/migration-batches/{id}/live-progress")
    @PreAuthorize("hasRole('system_admin')")
    @Operation(summary = "Lightweight in-process live throughput for the selected migration batch")
    public ResponseEntity<ApiResponse<MigrationProgressTracker.Snapshot>> liveProgress(
            @PathVariable UUID id) {
        MigrationBatchResponse batch = migrationService.getBatchById(id);
        return ResponseEntity.ok(ApiResponse.success(progressTracker == null
                ? null : progressTracker.snapshot(id, batch.migrationSourceId())));
    }

    @PostMapping("/migration-batches/{id}/run")
    @PreAuthorize("hasRole('system_admin')")
    @Operation(summary = "Start a migration batch in the background")
    public ResponseEntity<ApiResponse<Map<String, String>>> runBatch(
            @Parameter(description = "Batch UUID") @PathVariable UUID id,
            @Valid @RequestBody MigrationRunRequest runRequest) {
        migrationService.runBatch(id, runRequest);
        return ResponseEntity.accepted()
                .body(ApiResponse.success(
                    Map.of("message", "Migration batch " + id + " started", "status", "ACCEPTED"),
                    "Migration batch started"));
    }

    public record FailedFilesRetryRequest(@jakarta.validation.constraints.NotEmpty
                                          @jakarta.validation.constraints.Size(max = 100)
                                          List<@jakarta.validation.constraints.NotNull Integer> fileIds) {}

    @PostMapping("/migration-batches/{id}/retry-failed-files")
    @PreAuthorize("hasRole('system_admin')")
    @Operation(summary = "Retry 1–100 FAILED source files in the selected migration batch")
    public ResponseEntity<ApiResponse<Map<String, String>>> retryFailedFiles(
            @PathVariable UUID id,
            @Valid @RequestBody FailedFilesRetryRequest request) {
        migrationService.retryFailedFiles(id, request.fileIds());
        return ResponseEntity.accepted().body(ApiResponse.success(
                Map.of("status", "ACCEPTED", "message", "Selected failed files queued for retry"),
                "Selective retry accepted"));
    }

    @PostMapping("/migration-batches/{id}/stop")
    @PreAuthorize("hasRole('system_admin')")
    @Operation(summary = "Request a safe stop for a running migration batch")
    public ResponseEntity<ApiResponse<MigrationBatchResponse>> stopBatch(
            @Parameter(description = "Batch UUID") @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(
                migrationService.stopBatch(id),
                "Migration batch stop requested"));
    }

    @PostMapping("/migration-batches/{id}/complete")
    @PreAuthorize("hasRole('system_admin')")
    @Operation(summary = "Mark a migration batch as completed")
    public ResponseEntity<ApiResponse<MigrationBatchResponse>> completeBatch(
            @Parameter(description = "Batch UUID") @PathVariable UUID id,
            @Valid @RequestBody MigrationBatchCompleteRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                migrationService.completeBatch(id, request),
                "Migration batch completed"));
    }
}
