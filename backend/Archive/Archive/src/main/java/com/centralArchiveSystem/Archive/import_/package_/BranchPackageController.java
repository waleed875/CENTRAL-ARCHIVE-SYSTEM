package com.centralArchiveSystem.Archive.import_.package_;

import com.centralArchiveSystem.Archive.auth.CurrentUserService;
import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import com.centralArchiveSystem.Archive.common.dto.PagedResponse;
import com.centralArchiveSystem.Archive.import_.operation.ImportOperation;
import com.centralArchiveSystem.Archive.import_.operation.ImportOperationService;
import com.centralArchiveSystem.Archive.import_.package_.dto.PackageImportRequest;
import com.centralArchiveSystem.Archive.import_.package_.dto.PackageResponse;
import com.centralArchiveSystem.Archive.import_.package_.dto.PackageVerifyResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/packages")
@Tag(name = "Branch Packages", description = "Offline branch package import workflow")
public class BranchPackageController {

    private final BranchPackageService branchPackageService;
    private final CurrentUserService currentUserService;
    private final ImportOperationService importOperationService;
    private final ObjectMapper objectMapper;

    public BranchPackageController(BranchPackageService branchPackageService,
                                   CurrentUserService currentUserService,
                                   ImportOperationService importOperationService,
                                   ObjectMapper objectMapper) {
        this.branchPackageService = branchPackageService;
        this.currentUserService = currentUserService;
        this.importOperationService = importOperationService;
        this.objectMapper = objectMapper;
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
    @Operation(summary = "Receive a branch package; encrypted .zip/.nxpkg packages are decrypted during upload")
    public ResponseEntity<ApiResponse<PackageResponse>> receive(
            @Parameter(description = "Package archive (.pkg, .zip or .nxpkg)", required = true)
            @RequestParam("package_file") MultipartFile packageFile,
            @Parameter(description = "Passphrase for encrypted .zip/.nxpkg packages; never persisted")
            @RequestParam(value = "passphrase", required = false) String passphrase) throws Exception {

        PackageResponse response = branchPackageService.receive(
                packageFile,
                passphrase,
                currentUserService.userId()
        );

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
    @Operation(summary = "Verify stored package hash, manifest and file checksums")
    public ResponseEntity<ApiResponse<PackageVerifyResponse>> verify(@PathVariable UUID id) {
        PackageVerifyResponse result = branchPackageService.verify(id);
        String message = result.valid() ? "Package verified" : "Package verification failed";
        return ResponseEntity.ok(ApiResponse.success(result, message));
    }

    @PostMapping("/{id}/import")
    @PreAuthorize("hasAnyRole('system_admin', 'import_officer')")
    @Operation(summary = "Import a previously verified package")
    public ResponseEntity<ApiResponse<Map<String, String>>> importPackage(
            @PathVariable UUID id,
            @Valid @RequestBody PackageImportRequest request) {

        branchPackageService.assertImportable(id, request);

        UUID importedBy = currentUserService.userId();
        String requestSnapshot = objectMapper.valueToTree(request).toString();
        ImportOperation operation = importOperationService.create(id, importedBy, requestSnapshot);

        branchPackageService.importPackage(
                id,
                request,
                importedBy,
                operation.getId()
        );

        return ResponseEntity.accepted()
                .body(ApiResponse.success(
                    Map.of(
                        "import_id", operation.getId().toString(),
                        "status", ImportOperation.Status.STARTED.name(),
                        "message", "Import process started for package: " + id
                    ),
                    "Import process started"
                ));
    }

    @GetMapping("/{id}/references")
    @Operation(summary = "Get source reference IDs that may require central mappings before import")
    public ResponseEntity<ApiResponse<Map<String, List<UUID>>>> getReferences(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(branchPackageService.getReferenceRequirements(id)));
    }

    @GetMapping("/{id}/projects")
    @Operation(summary = "Get central project links created from a package")
    public ResponseEntity<ApiResponse<List<PackageProject>>> getProjects(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(branchPackageService.getProjects(id)));
    }

    @GetMapping("/{id}/operations")
    @Operation(summary = "Get package operation status and statistics")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getOperations(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(branchPackageService.getOperations(id)));
    }
}
