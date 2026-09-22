package com.centralArchiveSystem.Archive.project.filetype;

import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import com.centralArchiveSystem.Archive.common.dto.PagedResponse;
import com.centralArchiveSystem.Archive.project.filetype.dto.FileTypeRequest;
import com.centralArchiveSystem.Archive.project.filetype.dto.FileTypeResponse;
import com.centralArchiveSystem.Archive.project.filetype.dto.FileTypeUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/file-types")
public class FileTypeController {

    private final FileTypeService fileTypeService;

    public FileTypeController(FileTypeService fileTypeService) {
        this.fileTypeService = fileTypeService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<FileTypeResponse>>> list(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<FileTypeResponse> result = fileTypeService.list(categoryId, search, isActive, page - 1, size);
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.fromSpring(result, page, size)));
    }

    @GetMapping("/by-category/{categoryId}")
    public ResponseEntity<ApiResponse<List<FileTypeResponse>>> listByCategory(
            @PathVariable UUID categoryId,
            @RequestParam(required = false) Boolean isActive) {
        return ResponseEntity.ok(ApiResponse.success(fileTypeService.listByCategory(categoryId, isActive)));
    }

    @PostMapping
    @PreAuthorize("hasRole('system_admin')")
    public ResponseEntity<ApiResponse<FileTypeResponse>> create(@Valid @RequestBody FileTypeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(fileTypeService.create(request), "File type created successfully"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<FileTypeResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(fileTypeService.getById(id)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('system_admin')")
    public ResponseEntity<ApiResponse<FileTypeResponse>> update(
            @PathVariable UUID id,
            @RequestBody FileTypeUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(fileTypeService.update(id, request), "File type updated successfully"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('system_admin')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        fileTypeService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null, "File type deactivated successfully"));
    }
}
