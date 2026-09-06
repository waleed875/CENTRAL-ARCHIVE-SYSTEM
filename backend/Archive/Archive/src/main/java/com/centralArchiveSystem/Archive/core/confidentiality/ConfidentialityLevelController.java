package com.centralArchiveSystem.Archive.core.confidentiality;

import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import com.centralArchiveSystem.Archive.common.dto.PagedResponse;
import com.centralArchiveSystem.Archive.core.confidentiality.dto.ConfidentialityLevelRequest;
import com.centralArchiveSystem.Archive.core.confidentiality.dto.ConfidentialityLevelResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/confidentiality-levels")
@Tag(name = "Confidentiality Levels", description = "CRUD operations for confidentiality levels")
public class ConfidentialityLevelController {

    private final ConfidentialityLevelService confidentialityLevelService;

    public ConfidentialityLevelController(ConfidentialityLevelService confidentialityLevelService) {
        this.confidentialityLevelService = confidentialityLevelService;
    }

    @GetMapping
    @Operation(summary = "List all confidentiality levels with pagination")
    public ResponseEntity<ApiResponse<PagedResponse<ConfidentialityLevelResponse>>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ConfidentialityLevelResponse> result = confidentialityLevelService.list(page - 1, size);
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.fromSpring(result, page, size)));
    }

    @PostMapping
    @PreAuthorize("hasRole('system_admin')")
    @Operation(summary = "Create a new confidentiality level")
    public ResponseEntity<ApiResponse<ConfidentialityLevelResponse>> create(
            @Valid @RequestBody ConfidentialityLevelRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(confidentialityLevelService.create(request), "Confidentiality level created"));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a confidentiality level by ID")
    public ResponseEntity<ApiResponse<ConfidentialityLevelResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(confidentialityLevelService.getById(id)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('system_admin')")
    @Operation(summary = "Update a confidentiality level")
    public ResponseEntity<ApiResponse<ConfidentialityLevelResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody ConfidentialityLevelRequest request) {
        return ResponseEntity.ok(ApiResponse.success(confidentialityLevelService.update(id, request), "Confidentiality level updated"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('system_admin')")
    @Operation(summary = "Delete a confidentiality level")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        confidentialityLevelService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Confidentiality level deleted"));
    }
}
