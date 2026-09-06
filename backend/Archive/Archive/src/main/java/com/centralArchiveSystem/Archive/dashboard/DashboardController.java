package com.centralArchiveSystem.Archive.dashboard;

import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import com.centralArchiveSystem.Archive.dashboard.dto.ActivityResponse;
import com.centralArchiveSystem.Archive.dashboard.dto.DashboardStatsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard", description = "Dashboard statistics and activity endpoints")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/stats")
    @Operation(summary = "Get aggregated dashboard statistics")
    public ResponseEntity<ApiResponse<DashboardStatsResponse>> getStats() {
        DashboardStatsResponse stats = dashboardService.getStats();
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @GetMapping("/activity")
    @Operation(summary = "Get recent activity across the system")
    public ResponseEntity<ApiResponse<List<ActivityResponse>>> getActivity(
            @Parameter(description = "Maximum number of activity items to return")
            @RequestParam(defaultValue = "20") int limit) {
        List<ActivityResponse> activity = dashboardService.getRecentActivity(limit);
        return ResponseEntity.ok(ApiResponse.success(activity));
    }
}
