package com.centralArchiveSystem.Archive.import_.package_;

import com.centralArchiveSystem.Archive.auth.CurrentUserService;
import com.centralArchiveSystem.Archive.common.exception.BusinessRuleException;
import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import com.centralArchiveSystem.Archive.common.dto.PagedResponse;
import com.centralArchiveSystem.Archive.import_.operation.ImportOperation;
import com.centralArchiveSystem.Archive.import_.operation.ImportOperationService;
import com.centralArchiveSystem.Archive.import_.package_.dto.PackageImportRequest;
import com.centralArchiveSystem.Archive.import_.package_.dto.PackageResponse;
import com.centralArchiveSystem.Archive.import_.package_.dto.PackageVerifyResponse;
import com.centralArchiveSystem.Archive.import_.package_.dto.PackagePreviewResponse;
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

    @GetMapping("/{id}/preview")
    @PreAuthorize("hasAnyRole('system_admin', 'import_officer')")
    @Operation(summary = "Read-only preview of a verified package; no import side effects")
    public ResponseEntity<ApiResponse<PackagePreviewResponse>> preview(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(branchPackageService.preview(id)));
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

        if (request.targetProjectId() != null) {
            throw new IllegalArgumentException(
                    "Use /import-as-version with system_admin privileges for existing project versions");
        }
        try {
            branchPackageService.assertImportable(id, request);
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            throw new BusinessRuleException(importValidationMessage(rejected.getMessage()));
        }

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

    @PostMapping("/{id}/import-as-version")
    @PreAuthorize("hasRole('system_admin')")
    @Operation(summary = "Import one verified project package as a new version of an explicitly selected project")
    public ResponseEntity<ApiResponse<Map<String, String>>> importPackageAsVersion(
            @PathVariable UUID id,
            @Valid @RequestBody PackageImportRequest request) {
        if (request.targetProjectId() == null) {
            throw new IllegalArgumentException("target_project_id is required");
        }
        try {
            branchPackageService.assertImportable(id, request);
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            throw new BusinessRuleException(importValidationMessage(rejected.getMessage()));
        }
        UUID importedBy = currentUserService.userId();
        String requestSnapshot = objectMapper.valueToTree(request).toString();
        ImportOperation operation = importOperationService.create(id, importedBy, requestSnapshot);
        branchPackageService.importPackage(id, request, importedBy, operation.getId());
        return ResponseEntity.accepted().body(ApiResponse.success(
                Map.of("import_id", operation.getId().toString(),
                        "status", ImportOperation.Status.STARTED.name(),
                        "message", "New-version import started for package: " + id),
                "New-version import started"));
    }

    /** Expose actionable but non-sensitive error text, never raw manifest/SQL/MinIO details. */
    private static String importValidationMessage(String reason) {
        if (reason == null) return "تعذر بدء استيراد الحزمة؛ راجع البيانات المطلوبة والصلاحيات";
        String value = reason.toLowerCase(java.util.Locale.ROOT);
        if (value.contains("verified") || value.contains("package must")) {
            return "الحزمة ليست في حالة «تم التحقق». تحقق منها أولًا؛ الحزمة المستوردة مسبقًا لا تحتاج إعادة استيراد";
        }
        if (value.contains("organization") || value.contains("target org")) {
            return "تعذر الاستيراد: اختر الجهة المركزية المطابقة لجميع الجهات الموجودة في الحزمة";
        }
        if (value.contains("category") || value.contains("file categor")) {
            return "تعذر الاستيراد: اختر فئة ملفات مركزية نشطة، وحدد الفئة الافتراضية للملفات غير المصنفة";
        }
        if (value.contains("file type") || value.contains("project type")) {
            return "تعذر الاستيراد: تصنيف أو نوع الملف/المشروع غير مطابق للمراجع المركزية";
        }
        if (value.contains("confidentiality") || value.contains("clearance")) {
            return "تعذر الاستيراد: مستوى سرية المشروع أو أحد الملفات غير متوافق مع الصلاحيات أو الربط المحدد";
        }
        if (value.contains("latest") || value.contains("approved") || value.contains("archived")
                || value.contains("rejected")) {
            return "اختر أحدث إصدار من مشروع معتمد أو مؤرشف أو مرفوض قبل إضافة إصدار جديد";
        }
        if (value.contains("project number") || value.contains("must match") || value.contains("year")) {
            return "تعذر إضافة الإصدار: يجب تطابق الجهة ورقم المشروع والسنة مع المشروع الذي اخترته";
        }
        if (value.contains("exactly one") || value.contains("schema")) {
            return "إضافة إصدار جديد تتطلب حزمة تحتوي مشروعًا واحدًا وبصيغة بيانات مدعومة";
        }
        return "تعذر بدء الاستيراد: راجع ربط الجهات والتصنيفات ومستويات السرية وحالة الحزمة";
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
