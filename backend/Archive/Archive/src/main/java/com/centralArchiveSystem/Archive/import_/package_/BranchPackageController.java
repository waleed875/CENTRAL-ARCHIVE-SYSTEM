package com.centralArchiveSystem.Archive.import_.package_;

import com.centralArchiveSystem.Archive.auth.CurrentUserService;\nimport com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import com.centralArchiveSystem.Archive.common.dto.PagedResponse;
import com.centralArchiveSystem.Archive.import_.package_.dto.PackageImportRequest;
import com.centralArchiveSystem.Archive.import_.package_.dto.PackageResponse;
import com.centralArchiveSystem.Archive.import_.package_.dto.PackageVerifyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/packages")
@Tag(name = "Branch Packages", description = "Branch package import/export endpoints")
public class BranchPackageController {

    private final BranchPackageService branchPackageService;
    private final CurrentUserService currentUserService;

    public BranchPackageController(BranchPackageService branchPackageService,
                                   CurrentUserService currentUserService) {
        this.branchPackageService = branchPackageService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    @Operation(summary = "List packages with filtering and pagination")
    public ResponseEntity<ApiResponse<PagedResponse<PackageResponse>>> list(
            @Parameter(description = "Search term") @RequestParam(required = false) String searchTerm,
            @Parameter(description = "Package status") @RequestParam(required = false) BranchPackage.PackageStatus status,
            @Parameter(description = "Source organization ID") @RequestParam(required = false) UUID sourceOrganizationId,
            @Parameter(description = "Page number (1-based)") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size) {

        Page<PackageResponse> result = branchPackageService.list(
                searchTerm, status, sourceOrganizationId, page - 1, size);
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.fromSpring(result, page, size)));
    }

    @PostMapping(value = "/receive", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('system_admin', 'import_officer')")
    @Operation(summary = "Receive a branch package by uploading manifest file")
    public ResponseEntity<ApiResponse<PackageResponse>> receive(
            @Parameter(description = "Manifest file (JSON)", required = true)
            @RequestParam("file") MultipartFile file) throws IOException {

        String manifestJson = new String(file.getBytes(), StandardCharsets.UTF_8);
        PackageResponse response = branchPackageService.receive(manifestJson, currentUserService.username());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Package received"));
    }

    @PostMapping(value = "/receive/json", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('system_admin', 'import_officer')")
    @Operation(summary = "Receive a branch package by submitting JSON manifest directly")
    public ResponseEntity<ApiResponse<PackageResponse>> receiveJson(
            @RequestBody String manifestJson) {

        PackageResponse response = branchPackageService.receive(manifestJson, currentUserService.username());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Package received"));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get package by ID")
    public ResponseEntity<ApiResponse<PackageResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(branchPackageService.getById(id)));
    }

    @PostMapping("/{id}/verify")
    @PreAuthorize("hasAnyRole('system_admin', 'import_officer')")
    @Operation(summary = "Verify package integrity and manifest")
    public ResponseEntity<ApiResponse<PackageVerifyResponse>> verify(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(branchPackageService.verify(id), "Package verified"));
    }

    @PostMapping("/{id}/import")
    @PreAuthorize("hasAnyRole('system_admin', 'import_officer')")
    @Operation(summary = "Import package projects and files (background process)")
    public ResponseEntity<ApiResponse<Map<String, String>>> importPackage(
            @PathVariable UUID id,
            @Valid @RequestBody PackageImportRequest request) {

        PackageImportRequest authenticatedRequest = new PackageImportRequest(
                request.targetOrganizationId(),
                request.organizationMapping(),
                request.skipDuplicates(),
                request.overwriteExisting(),
                currentUserService.username()
        );
        branchPackageService.importPackage(id, authenticatedRequest);
        return ResponseEntity.accepted()
                .body(ApiResponse.success(
                    Map.of(
                        "message", "Import process started for package: " + id,
                        "status", "ACCEPTED"
                    ),
                    "Import process started"
                ));
    }

    @GetMapping("/{id}/projects")
    @Operation(summary = "Get projects in a package")
    public ResponseEntity<ApiResponse<List<PackageProject>>> getProjects(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(branchPackageService.getProjects(id)));
    }

    @GetMapping("/{id}/operations")
    @Operation(summary = "Get package operation status and statistics")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getOperations(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(branchPackageService.getOperations(id)));
    }
}
