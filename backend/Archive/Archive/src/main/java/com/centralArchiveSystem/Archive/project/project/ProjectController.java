package com.centralArchiveSystem.Archive.project.project;

import com.centralArchiveSystem.Archive.auth.CurrentUserService;
import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import com.centralArchiveSystem.Archive.common.dto.PagedResponse;
import com.centralArchiveSystem.Archive.project.project.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects")
@Tag(name = "Projects", description = "Project management endpoints")
public class ProjectController {
    private final ProjectService projectService;
    private final CurrentUserService currentUserService;
    private final IncompleteProjectReportService incompleteProjectReportService;
    public ProjectController(ProjectService projectService, CurrentUserService currentUserService,
                             IncompleteProjectReportService incompleteProjectReportService) {
        this.projectService = projectService;
        this.currentUserService = currentUserService;
        this.incompleteProjectReportService = incompleteProjectReportService;
    }
    @GetMapping
    @Operation(summary = "List projects with filtering and pagination")
    public ResponseEntity<ApiResponse<PagedResponse<ProjectResponse>>> list(
            @RequestParam(required = false) UUID organizationId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Project.ProjectStatus status,
            @Parameter(description = "Page number (1-based)") @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ProjectResponse> projects = projectService.list(organizationId, year, status, page - 1, size);
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.fromSpring(projects, page, size)));
    }
    @PostMapping
    @PreAuthorize("hasAnyRole('system_admin', 'central_data_entry')")
    @Operation(summary = "Create a new project")
    public ResponseEntity<ApiResponse<ProjectResponse>> create(@Valid @RequestBody ProjectRequest request) {
        ProjectResponse response = projectService.create(request, currentUserService.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response, "Project created successfully"));
    }
    @GetMapping("/{id}")
    @Operation(summary = "Get project by ID")
    public ResponseEntity<ApiResponse<ProjectResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(projectService.getById(id)));
    }
    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('system_admin', 'central_data_entry')")
    @Operation(summary = "Update a project")
    public ResponseEntity<ApiResponse<ProjectResponse>> update(@PathVariable UUID id,
            @Valid @RequestBody ProjectUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(projectService.update(id, request), "Project updated successfully"));
    }
    @PostMapping("/{id}/new-version")
    @PreAuthorize("hasAnyRole('system_admin', 'central_data_entry')")
    @Operation(summary = "Create a new version of a project")
    public ResponseEntity<ApiResponse<ProjectResponse>> newVersion(@PathVariable UUID id,
            @Valid @RequestBody(required = false) ProjectUpdateRequest overrides) {
        ProjectResponse response = projectService.newVersion(id, currentUserService.userId(), overrides);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response, "New project version created"));
    }
    @GetMapping("/{id}/versions")
    @Operation(summary = "Get all versions of a project")
    public ResponseEntity<ApiResponse<List<ProjectResponse>>> getVersions(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(projectService.getVersions(id)));
    }
    @PatchMapping("/{id}/submit-for-review")
    @PreAuthorize("hasAnyRole('system_admin', 'central_data_entry')")
    @Operation(summary = "Submit a project for review")
    public ResponseEntity<ApiResponse<ProjectResponse>> submitForReview(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(projectService.submitForReview(id, currentUserService.userId()), "Project submitted for review"));
    }
    @PatchMapping("/{id}/request-correction")
    @PreAuthorize("hasAnyRole('system_admin', 'auditor')")
    @Operation(summary = "Request correction on a project under review")
    public ResponseEntity<ApiResponse<ProjectResponse>> requestCorrection(@PathVariable UUID id,
            @Valid @RequestBody ProjectCorrectionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(projectService.requestCorrection(id, request.reviewNotes()), "Correction requested"));
    }
    @PatchMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('system_admin', 'auditor')")
    @Operation(summary = "Approve a project under review")
    public ResponseEntity<ApiResponse<ProjectResponse>> approve(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(projectService.approve(id, currentUserService.userId()), "Project approved"));
    }
    @PatchMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('system_admin', 'auditor')")
    @Operation(summary = "Reject a project under review")
    public ResponseEntity<ApiResponse<ProjectResponse>> reject(@PathVariable UUID id,
            @Valid @RequestBody ProjectRejectionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(projectService.reject(id, request.rejectionReason()), "Project rejected"));
    }
    @PatchMapping("/{id}/reopen")
    @PreAuthorize("hasAnyRole('system_admin', 'central_data_entry')")
    @Operation(summary = "Reopen a rejected latest project for correction")
    public ResponseEntity<ApiResponse<ProjectResponse>> reopenRejected(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(projectService.reopenRejected(id), "Project reopened for correction"));
    }
    @PatchMapping("/{id}/archive")
    @PreAuthorize("hasAnyRole('system_admin', 'archive_officer')")
    @Operation(summary = "Archive an approved project")
    public ResponseEntity<ApiResponse<ProjectResponse>> archive(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(projectService.archive(id), "Project archived"));
    }
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('system_admin', 'central_data_entry')")
    @Operation(summary = "Delete an unused standalone draft project")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        projectService.deleteDraft(id);
        return ResponseEntity.ok(ApiResponse.success(null, "تم حذف المشروع"));
    }
    @GetMapping("/search")
    @Operation(summary = "Search projects with full-text and filter parameters")
    public ResponseEntity<ApiResponse<PagedResponse<ProjectResponse>>> search(
            @RequestParam(required = false) String searchTerm,
            @RequestParam(required = false) UUID organizationId,
            @RequestParam(required = false) Project.ProjectStatus status,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) UUID projectTypeId,
            @RequestParam(required = false) UUID confidentialityLevelId,
            @RequestParam(required = false) Project.SourceType sourceType,
            @RequestParam(required = false) UUID projectUid,
            @RequestParam(required = false) UUID createdBy,
            @RequestParam(required = false) UUID approvedBy,
            @RequestParam(defaultValue = "true") Boolean isLatestVersion,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDirection) {
        ProjectSearchRequest request = new ProjectSearchRequest(searchTerm, organizationId, status, year,
                projectTypeId, confidentialityLevelId, sourceType, projectUid, createdBy, approvedBy,
                isLatestVersion, null, null, sortBy, sortDirection);
        Page<ProjectResponse> results = projectService.search(request, page - 1, size);
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.fromSpring(results, page, size)));
    }
    @GetMapping("/stats")
    @Operation(summary = "Get project statistics")
    public ResponseEntity<ApiResponse<ProjectStatsResponse>> getStats(@RequestParam(required = false) UUID organizationId) {
        return ResponseEntity.ok(ApiResponse.success(projectService.getStats(organizationId)));
    }
    @GetMapping("/incomplete-report")
    @PreAuthorize("hasRole('system_admin')")
    @Operation(summary = "List projects that entered the platform with missing mandatory fields")
    public ResponseEntity<ApiResponse<IncompleteProjectReportResponse>> getIncompleteReport() {
        return ResponseEntity.ok(ApiResponse.success(incompleteProjectReportService.buildReport()));
    }
}
