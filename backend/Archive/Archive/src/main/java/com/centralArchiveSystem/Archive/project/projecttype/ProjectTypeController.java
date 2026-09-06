package com.centralArchiveSystem.Archive.project.projecttype;

import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import com.centralArchiveSystem.Archive.common.dto.PagedResponse;
import com.centralArchiveSystem.Archive.project.projecttype.dto.ProjectTypeRequest;
import com.centralArchiveSystem.Archive.project.projecttype.dto.ProjectTypeResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/project-types")
public class ProjectTypeController {

    private final ProjectTypeService projectTypeService;

    public ProjectTypeController(ProjectTypeService projectTypeService) {
        this.projectTypeService = projectTypeService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<ProjectTypeResponse>>> list(
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ProjectTypeResponse> result = projectTypeService.list(isActive, page - 1, size);
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.fromSpring(result, page, size)));
    }

    @GetMapping("/all")
    public ResponseEntity<ApiResponse<List<ProjectTypeResponse>>> listAll() {
        return ResponseEntity.ok(ApiResponse.success(projectTypeService.listAll()));
    }

    @PostMapping
    @PreAuthorize("hasRole('system_admin')")
    public ResponseEntity<ApiResponse<ProjectTypeResponse>> create(@Valid @RequestBody ProjectTypeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(projectTypeService.create(request), "Project type created"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ProjectTypeResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(projectTypeService.getById(id)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('system_admin')")
    public ResponseEntity<ApiResponse<ProjectTypeResponse>> update(@PathVariable UUID id, @Valid @RequestBody ProjectTypeRequest request) {
        return ResponseEntity.ok(ApiResponse.success(projectTypeService.update(id, request), "Project type updated"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('system_admin')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        projectTypeService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Project type deleted"));
    }
}
