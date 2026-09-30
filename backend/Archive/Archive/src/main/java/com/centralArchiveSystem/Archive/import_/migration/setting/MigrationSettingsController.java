package com.centralArchiveSystem.Archive.import_.migration.setting;

import com.centralArchiveSystem.Archive.auth.CurrentUserService;
import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import com.centralArchiveSystem.Archive.import_.migration.setting.dto.MigrationSettingsResponse;
import com.centralArchiveSystem.Archive.import_.migration.setting.dto.MigrationSettingsUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the legacy migration switch to the UI. Nothing else is writable here on purpose:
 * connection strings, database credentials and MinIO settings stay in the environment.
 */
@RestController
@RequestMapping("/api/v1/migration-settings")
@Tag(name = "Migration", description = "Import / Migration management")
public class MigrationSettingsController {

    private final MigrationSettingsService settingsService;
    private final CurrentUserService currentUserService;

    public MigrationSettingsController(MigrationSettingsService settingsService,
                                       CurrentUserService currentUserService) {
        this.settingsService = settingsService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    @PreAuthorize("hasRole('system_admin')")
    @Operation(summary = "Get the runtime-editable legacy migration settings")
    public ResponseEntity<ApiResponse<MigrationSettingsResponse>> getSettings() {
        return ResponseEntity.ok(ApiResponse.success(settingsService.getSettings()));
    }

    @PutMapping
    @PreAuthorize("hasRole('system_admin')")
    @Operation(summary = "Update the legacy migration settings; takes effect without a restart")
    public ResponseEntity<ApiResponse<MigrationSettingsResponse>> updateSettings(
            @Valid @RequestBody MigrationSettingsUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                settingsService.updateSettings(request, currentUserService.userId()),
                "Migration settings updated"));
    }
}
