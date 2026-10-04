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
            @Parameter(description = "Organization ID") @RequestParam(required = false) UUID organizationId,
            @Parameter(description = "Year") @RequestParam(required = false) Integer year,
            @Parameter(description = "Status") @RequestParam(required = false) Project.ProjectStatus status,
            @Parameter(description = "Page number (1-based)") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size) {

        Page<ProjectResponse> projects = projectService.list(organizationId, year, status, page - 1, size);
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.fromSpring(projects, page, size)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('system_admin', 'central_data_entry')")
    @Operation(summary = "Create a new project")
    public ResponseEntity<ApiResponse<ProjectResponse>> create(@Valid @RequestBody ProjectRequest request) {
        ProjectResponse response = projectService.create(request, currentUserService.userId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Project created successfully"));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get project by ID")
    public ResponseEntity<ApiResponse<ProjectResponse>> getById(@PathVariable UUID id) {
        ProjectResponse response = projectService.getById(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('system_admin', 'central_data_entry')")
    @Operation(summary = "Update a project")
    public ResponseEntity<ApiResponse<ProjectResponse>> update(@PathVariable UUID id,
                                                   @Valid @RequestBody ProjectUpdateRequest request) {
        ProjectResponse response = projectService.update(id, request);
        return ResponseEntity.ok(ApiResponse.success(response, "Project updated successfully"));
    }

    @PostMapping("/{id}/new-version")
    @PreAuthorize("hasAnyRole('system_admin', 'central_data_entry')")
    @Operation(summary = "Create a new version of a project")
    public ResponseEntity<ApiResponse<ProjectResponse>> newVersion(
            @PathVariable UUID id,
            @RequestBody(required = false) ProjectUpdateRequest overrides) {
        ProjectResponse response = projectService.newVersion(
                id,
                currentUserService.userId(),
                overrides
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "New project version created"));
    }

    @GetMapping("/{id}/versions")
    @Operation(summary = "Get all versions of a project")
    public ResponseEntity<ApiResponse<List<ProjectResponse>>> getVersions(@PathVariable UUID id) {
        List<ProjectResponse> versions = projectService.getVersions(id);
        return ResponseEntity.ok(ApiResponse.success(versions));
    }

    @PatchMapping("/{id}/submit-for-review")
    @PreAuthorize("hasAnyRole('system_admin', 'central_data_entry')")
    @Operation(summary = "Submit a project for review")
    public ResponseEntity<ApiResponse<ProjectResponse>> submitForReview(@PathVariable UUID id) {
        ProjectResponse response = projectService.submitForReview(id);
        return ResponseEntity.ok(ApiResponse.success(response, "Project submitted for review"));
    }

    @PatchMapping("/{id}/request-correction")
    @PreAuthorize("hasAnyRole('system_admin', 'auditor')")
    @Operation(summary = "Request correction on a project under review")
    public ResponseEntity<ApiResponse<ProjectResponse>> requestCorrection(
            @PathVariable UUID id,
            @Valid @RequestBody ProjectCorrectionRequest request) {
        ProjectResponse response = projectService.requestCorrection(id, request.reviewNotes());
        return ResponseEntity.ok(ApiResponse.success(response, "Correction requested"));
    }

    @PatchMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('system_admin', 'auditor')")
    @Operation(summary = "Approve a project under review")
    public ResponseEntity<ApiResponse<ProjectResponse>> approve(@PathVariable UUID id) {
        ProjectResponse response = projectService.approve(id, currentUserService.userId());
        return ResponseEntity.ok(ApiResponse.success(response, "Project approved"));
    }

    @PatchMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('system_admin', 'auditor')")
    @Operation(summary = "Reject a project under review")
    public ResponseEntity<ApiResponse<ProjectResponse>> reject(
            @PathVariable UUID id,
            @Valid @RequestBody ProjectRejectionRequest request) {
        ProjectResponse response = projectService.reject(id, request.rejectionReason());
        return ResponseEntity.ok(ApiResponse.success(response, "Project rejected"));
    }

    @PatchMapping("/{id}/archive")
    @PreAuthorize("hasAnyRole('system_admin', 'archive_officer')")
    @Operation(summary = "Archive an approved project")
    public ResponseEntity<ApiResponse<ProjectResponse>> archive(@PathVariable UUID id) {
        ProjectResponse response = projectService.archive(id);
        return ResponseEntity.ok(ApiResponse.success(response, "Project archived"));
    }

    /**
     * Removes a draft entered by mistake.
     *
     * <p>Named apart from the archive transition deliberately: this is the only project removal the
     * system has, and it answers 422 rather than 404 for anything that has left the draft state, so
     * the refusal says which condition failed. A person who is looking at a project they did not
     * mean to create needs to be told that it can no longer be removed, not left to assume the
     * button did nothing.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('system_admin', 'central_data_entry')")
    @Operation(summary = "Delete a draft project that was created by mistake")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        projectService.deleteDraft(id);
        return ResponseEntity.ok(ApiResponse.success(null, "تم حذف المشروع"));
    }

    @GetMapping("/search")
    @Operation(summary = "Search projects with full-text and filter parameters")
    public ResponseEntity<ApiResponse<PagedResponse<ProjectResponse>>> search(
            @Parameter(description = "Search term") @RequestParam(required = false) String searchTerm,
            @Parameter(description = "Organization ID") @RequestParam(required = false) UUID organizationId,
            @Parameter(description = "Status") @RequestParam(required = false) Project.ProjectStatus status,
            @Parameter(description = "Year") @RequestParam(required = false) Integer year,
            @Parameter(description = "Project type ID") @RequestParam(required = false) UUID projectTypeId,
            @Parameter(description = "Confidentiality level ID") @RequestParam(required = false) UUID confidentialityLevelId,
            @Parameter(description = "Source type") @RequestParam(required = false) Project.SourceType sourceType,
            @Parameter(description = "Project UID") @RequestParam(required = false) UUID projectUid,
            @Parameter(description = "Created by user ID") @RequestParam(required = false) UUID createdBy,
            @Parameter(description = "Approved by user ID") @RequestParam(required = false) UUID approvedBy,
            @Parameter(description = "Latest version only") @RequestParam(defaultValue = "true") Boolean isLatestVersion,
            @Parameter(description = "Page number (1-based)") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Sort by field") @RequestParam(defaultValue = "createdAt") String sortBy,
            @Parameter(description = "Sort direction") @RequestParam(defaultValue = "desc") String sortDirection) {

        ProjectSearchRequest searchRequest = new ProjectSearchRequest(
                searchTerm,
                organizationId,
                status,
                year,
                projectTypeId,
                confidentialityLevelId,
                sourceType,
                projectUid,
                createdBy,
                approvedBy,
                isLatestVersion,
                null,
                null,
                sortBy,
                sortDirection
        );

        Page<ProjectResponse> results = projectService.search(searchRequest, page - 1, size);
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.fromSpring(results, page, size)));
    }

    @GetMapping("/stats")
    @Operation(summary = "Get project statistics")
    public ResponseEntity<ApiResponse<ProjectStatsResponse>> getStats(
            @Parameter(description = "Organization ID to filter stats") @RequestParam(required = false) UUID organizationId) {
        ProjectStatsResponse stats = projectService.getStats(organizationId);
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @GetMapping("/incomplete-report")
    @PreAuthorize("hasRole('system_admin')")
    @Operation(summary = "List projects that entered the platform with missing mandatory fields")
    public ResponseEntity<ApiResponse<IncompleteProjectReportResponse>> getIncompleteReport() {
        return ResponseEntity.ok(ApiResponse.success(incompleteProjectReportService.buildReport()));
    }
}
