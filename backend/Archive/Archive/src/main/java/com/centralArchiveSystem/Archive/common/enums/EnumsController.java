package com.centralArchiveSystem.Archive.common.enums;

import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/enums")
@Tag(name = "Enums", description = "Constrained value sets accepted by the API")
public class EnumsController {
    private final EnumCatalog enumCatalog;
    public EnumsController(EnumCatalog enumCatalog) { this.enumCatalog = enumCatalog; }

    @GetMapping
    @Operation(summary = "List every set of values the API restricts a field to")
    public ResponseEntity<ApiResponse<Map<String, List<String>>>> list() {
        return ResponseEntity.ok(ApiResponse.success(enumCatalog.values()));
    }

    @GetMapping("/labels")
    @Operation(summary = "Arabic labels keyed by the existing enum codes; wire values remain unchanged")
    public ResponseEntity<ApiResponse<Map<String, Map<String, String>>>> labels() {
        return ResponseEntity.ok(ApiResponse.success(ArabicEnumLabels.forCatalog(enumCatalog.values())));
    }
}
