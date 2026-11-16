package com.centralArchiveSystem.Archive.project.file;

import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import com.centralArchiveSystem.Archive.common.dto.PagedResponse;
import com.centralArchiveSystem.Archive.project.file.dto.GlobalFileSearchResult;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/files")
public class GlobalFileSearchController {
    private final GlobalFileSearchService service;

    public GlobalFileSearchController(GlobalFileSearchService service) {
        this.service = service;
    }

    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('system_admin', 'central_data_entry', 'auditor', 'archive_officer', 'import_officer', 'export_officer', 'read_only')")
    public ResponseEntity<ApiResponse<PagedResponse<GlobalFileSearchResult>>> search(
            @RequestParam(required = false) String fileName,
            @RequestParam(required = false) String extension,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID organizationId,
            @RequestParam(required = false) String projectNumber,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean includeVersions,
            @RequestParam(defaultValue = "newest") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = service.search(fileName, extension, categoryId, organizationId,
                projectNumber, year, from, to, includeVersions, sort, page, size);
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.fromSpring(
                result, result.getNumber() + 1, result.getSize())));
    }
}
