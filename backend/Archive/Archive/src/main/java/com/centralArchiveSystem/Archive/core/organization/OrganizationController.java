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
import java.util.Objects;
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
            @RequestParam(required = false) UUID parentId,
            @RequestParam(name = "parent_id", required = false) UUID parentIdAlias,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(name = "is_active", required = false) Boolean isActiveAlias,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Integer limit) {
        UUID parent = resolveAlias(parentId, parentIdAlias, "parentId/parent_id");
        Boolean active = resolveAlias(isActive, isActiveAlias, "isActive/is_active");
        int requestedSize = size != null ? size : (limit != null ? limit : 20);
        if (page < 1 || requestedSize < 1) {
            throw new IllegalArgumentException("رقم الصفحة وحجمها يجب أن يكونا موجبَين");
        }
        int effectiveSize = Math.min(requestedSize, 100);
        Page<OrganizationResponse> result = organizationService.list(
                search, parent, type, active, page - 1, effectiveSize);
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.fromSpring(result, page, effectiveSize)));
    }

    @GetMapping("/all")
    public ResponseEntity<ApiResponse<List<OrganizationResponse>>> listAll(
            @RequestParam(required = false) UUID parentId,
            @RequestParam(name = "parent_id", required = false) UUID parentIdAlias,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(name = "is_active", required = false) Boolean isActiveAlias) {
        UUID parent = resolveAlias(parentId, parentIdAlias, "parentId/parent_id");
        Boolean active = resolveAlias(isActive, isActiveAlias, "isActive/is_active");
        return ResponseEntity.ok(ApiResponse.success(organizationService.listAll(parent, type, active)));
    }

    /** Conflicting aliases must not silently select a different organization tree. */
    private static <T> T resolveAlias(T canonical, T alias, String field) {
        if (canonical != null && alias != null && !Objects.equals(canonical, alias)) {
            throw new IllegalArgumentException("قيمتان متعارضتان للمعامل " + field);
        }
        return canonical != null ? canonical : alias;
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
    public ResponseEntity<ApiResponse<OrganizationResponse>> update(@PathVariable UUID id, @Valid @RequestBody OrganizationUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(organizationService.update(id, request), "Organization updated"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('system_admin')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        organizationService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Organization deleted"));
    }
}
