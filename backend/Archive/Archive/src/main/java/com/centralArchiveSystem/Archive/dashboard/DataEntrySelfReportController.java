package com.centralArchiveSystem.Archive.dashboard;

import com.centralArchiveSystem.Archive.auth.CurrentUserService;
import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import com.centralArchiveSystem.Archive.common.dto.PagedResponse;
import com.centralArchiveSystem.Archive.dashboard.dto.DataEntryProjectRow;
import com.centralArchiveSystem.Archive.dashboard.dto.DataEntryProductivityResponse.UserProjects;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/data-entry-productivity/me")
@PreAuthorize("hasRole('central_data_entry')")
public class DataEntrySelfReportController {
    private final DataEntryProductivityService service;
    private final CurrentUserService currentUser;

    public DataEntrySelfReportController(DataEntryProductivityService service, CurrentUserService currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "Get the authenticated data-entry user's own project counts")
    public ApiResponse<UserProjects> summary() {
        return ApiResponse.success(service.mySummary(currentUser.userId()));
    }

    @GetMapping("/projects")
    @Operation(summary = "List the authenticated data-entry user's own projects")
    public ApiResponse<PagedResponse<DataEntryProjectRow>> projects(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.projects(currentUser.userId(), page, size));
    }
}
