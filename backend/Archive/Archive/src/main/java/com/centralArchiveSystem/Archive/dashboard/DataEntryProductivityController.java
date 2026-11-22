package com.centralArchiveSystem.Archive.dashboard;

import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import com.centralArchiveSystem.Archive.common.dto.PagedResponse;
import com.centralArchiveSystem.Archive.dashboard.dto.DataEntryProjectRow;
import com.centralArchiveSystem.Archive.dashboard.dto.DataEntryProductivityResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/data-entry-productivity")
@PreAuthorize("hasRole('system_admin')")
public class DataEntryProductivityController {
    private final DataEntryProductivityService service;

    public DataEntryProductivityController(DataEntryProductivityService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Count visible manually created projects per current data-entry user")
    public ApiResponse<DataEntryProductivityResponse> summary() {
        return ApiResponse.success(service.summary());
    }

    @GetMapping("/{userId}/projects")
    @Operation(summary = "Latest project versions attributed to a data-entry creator")
    public ApiResponse<PagedResponse<DataEntryProjectRow>> projects(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.projects(userId, page, size));
    }
}
