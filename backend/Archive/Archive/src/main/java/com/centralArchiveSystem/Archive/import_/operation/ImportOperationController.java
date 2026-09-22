package com.centralArchiveSystem.Archive.import_.operation;

import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Import Operations", description = "Track import operation status per package")
public class ImportOperationController {

    private final ImportOperationService service;

    public ImportOperationController(ImportOperationService service) {
        this.service = service;
    }

    @GetMapping("/packages/{packageId}/operations")
    @Operation(summary = "List all import operations for a package")
    public ResponseEntity<ApiResponse<List<ImportOperation>>> listByPackageId(
            @Parameter(description = "Package UUID") @PathVariable UUID packageId) {
        return ResponseEntity.ok(ApiResponse.success(service.getByPackageId(packageId)));
    }

}
