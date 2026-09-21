package com.centralArchiveSystem.Archive.core.organization;

import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import com.centralArchiveSystem.Archive.common.dto.PagedResponse;
import com.centralArchiveSystem.Archive.core.organization.dto.OrganizationRequest;
import com.centralArchiveSystem.Archive.core.organization.dto.OrganizationResponse;
import com.centralArchiveSystem.Archive.core.organization.dto.OrganizationTreeResponse;
import com.centralArchiveSystem.Archive.core.organization.dto.OrganizationUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations")
public class OrganizationController {

    private final OrganizationService organizationService;

    public OrganizationController(OrganizationService organizationService) {
        this.organizationService = organizationService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<OrganizationResponse>>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<OrganizationResponse> result = organizationService.list(search, type, isActive, page - 1, size);
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.fromSpring(result, page, size)));
    }

    @GetMapping("/tree")
    public ResponseEntity<ApiResponse<List<OrganizationTreeResponse>>> getTree() {
        return ResponseEntity.ok(ApiResponse.success(organizationService.getTree()));
    }

    @PostMapping
    @PreAuthorize("hasRole('system_admin')")
    public ResponseEntity<ApiResponse<OrganizationResponse>> create(@Valid @RequestBody OrganizationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(organizationService.create(request), "Organization created"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<OrganizationResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(organizationService.getById(id)));
    }

    @GetMapping("/{id}/tree")
    public ResponseEntity<ApiResponse<List<OrganizationTreeResponse>>> getSubtree(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(organizationService.getSubtree(id)));
    }

    @GetMapping("/{id}/path")
    public ResponseEntity<ApiResponse<List<OrganizationResponse>>> getPath(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(organizationService.getPath(id)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('system_admin')")
    public ResponseEntity<ApiResponse<OrganizationResponse>> update(@PathVariable UUID id, @RequestBody OrganizationUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(organizationService.update(id, request), "Organization updated"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('system_admin')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        organizationService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Organization deleted"));
    }
}
