package com.centralArchiveSystem.Archive.core.user;

import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import com.centralArchiveSystem.Archive.common.dto.PageRequest;
import com.centralArchiveSystem.Archive.common.dto.PagedResponse;
import com.centralArchiveSystem.Archive.core.user.dto.ChangePasswordRequest;
import com.centralArchiveSystem.Archive.core.user.dto.UserRequest;
import com.centralArchiveSystem.Archive.core.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "User management endpoints")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @Operation(summary = "List users with optional filtering and pagination")
    public ApiResponse<PagedResponse<UserResponse>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID organizationId,
            @RequestParam(required = false) User.UserStatus status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "DESC") String order) {
        PageRequest pageRequest = PageRequest.of(page, limit, sort, order);
        PagedResponse<UserResponse> response = userService.list(search, organizationId, status, pageRequest);
        return ApiResponse.success(response);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('system_admin')")
    @Operation(summary = "Create a new user")
    public ApiResponse<UserResponse> create(@Valid @RequestBody UserRequest request) {
        UserResponse response = userService.create(request);
        return ApiResponse.success(response, "User created successfully");
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get user by ID")
    public ApiResponse<UserResponse> getById(@PathVariable UUID id) {
        UserResponse response = userService.getById(id);
        return ApiResponse.success(response);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('system_admin')")
    @Operation(summary = "Update an existing user")
    public ApiResponse<UserResponse> update(@PathVariable UUID id, @Valid @RequestBody UserRequest request) {
        UserResponse response = userService.update(id, request);
        return ApiResponse.success(response, "User updated successfully");
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('system_admin')")
    @Operation(summary = "Soft-delete (disable) a user")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        userService.delete(id);
        return ApiResponse.success();
    }

    @GetMapping("/{id}/roles")
    @Operation(summary = "Get roles assigned to a user")
    public ApiResponse<Set<UserResponse.RoleSummary>> getRoles(@PathVariable UUID id) {
        Set<UserResponse.RoleSummary> roles = userService.getRoles(id);
        return ApiResponse.success(roles);
    }

    @PostMapping("/{id}/roles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('system_admin')")
    @Operation(summary = "Assign a role to a user")
    public ApiResponse<Void> addRole(@PathVariable UUID id, @RequestParam UUID roleId) {
        userService.addRole(id, roleId);
        return ApiResponse.success();
    }

    @DeleteMapping("/{id}/roles/{roleId}")
    @PreAuthorize("hasRole('system_admin')")
    @Operation(summary = "Remove a role from a user")
    public ApiResponse<Void> removeRole(@PathVariable UUID id, @PathVariable UUID roleId) {
        userService.removeRole(id, roleId);
        return ApiResponse.success();
    }
}
