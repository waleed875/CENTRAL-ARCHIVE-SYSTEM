package com.centralArchiveSystem.Archive.project.filecategory;

import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import com.centralArchiveSystem.Archive.common.dto.PagedResponse;
import com.centralArchiveSystem.Archive.project.filecategory.dto.FileCategoryRequest;
import com.centralArchiveSystem.Archive.project.filecategory.dto.FileCategoryResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/file-categories")
public class FileCategoryController {

    private final FileCategoryService fileCategoryService;

    public FileCategoryController(FileCategoryService fileCategoryService) {
        this.fileCategoryService = fileCategoryService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<FileCategoryResponse>>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<FileCategoryResponse> result = fileCategoryService.list(search, isActive, page - 1, size);
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.fromSpring(result, page, size)));
    }

    @GetMapping("/all")
    public ResponseEntity<ApiResponse<List<FileCategoryResponse>>> listAll(
            @RequestParam(required = false) Boolean isActive) {
        return ResponseEntity.ok(ApiResponse.success(fileCategoryService.listAll(isActive)));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<FileCategoryResponse>> create(@Valid @RequestBody FileCategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(fileCategoryService.create(request), "File category created successfully"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<FileCategoryResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(fileCategoryService.getById(id)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<FileCategoryResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody FileCategoryRequest request) {
        return ResponseEntity.ok(ApiResponse.success(fileCategoryService.update(id, request), "File category updated successfully"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        fileCategoryService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null, "File category deleted successfully"));
    }
}
