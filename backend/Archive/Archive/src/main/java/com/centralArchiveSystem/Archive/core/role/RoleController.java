package com.centralArchiveSystem.Archive.core.role;

import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import com.centralArchiveSystem.Archive.common.dto.PageRequest;
import com.centralArchiveSystem.Archive.common.dto.PagedResponse;
import com.centralArchiveSystem.Archive.core.role.dto.RoleRequest;
import com.centralArchiveSystem.Archive.core.role.dto.RoleResponse;
import com.centralArchiveSystem.Archive.core.role.dto.RoleUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/roles")
@Tag(name = "Roles", description = "Role management endpoints")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping
    @Operation(summary = "List roles with optional filtering and pagination")
    public ApiResponse<PagedResponse<RoleResponse>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "DESC") String order) {
        PageRequest pageRequest = PageRequest.of(page, limit, sort, order);
        PagedResponse<RoleResponse> response = roleService.list(search, isActive, pageRequest);
        return ApiResponse.success(response);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('system_admin')")
    @Operation(summary = "Create a new role")
    public ApiResponse<RoleResponse> create(@Valid @RequestBody RoleRequest request) {
        RoleResponse response = roleService.create(request);
        return ApiResponse.success(response, "Role created successfully");
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get role by ID")
    public ApiResponse<RoleResponse> getById(@PathVariable UUID id) {
        RoleResponse response = roleService.getById(id);
        return ApiResponse.success(response);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('system_admin')")
    @Operation(summary = "Update an existing role")
    public ApiResponse<RoleResponse> update(@PathVariable UUID id, @RequestBody RoleUpdateRequest request) {
        RoleResponse response = roleService.update(id, request);
        return ApiResponse.success(response, "Role updated successfully");
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('system_admin')")
    @Operation(summary = "Soft-deactivate a role (must have no active users)")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        roleService.delete(id);
        return ApiResponse.success();
    }
}
