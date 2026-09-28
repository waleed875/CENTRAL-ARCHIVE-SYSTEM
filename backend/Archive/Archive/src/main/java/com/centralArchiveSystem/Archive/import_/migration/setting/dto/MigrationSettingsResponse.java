package com.centralArchiveSystem.Archive.import_.migration.setting.dto;

/**
 * The only two environment settings the UI is allowed to change. Connection strings, credentials
 * and MinIO settings are intentionally absent: they stay in the environment.
 */
public record MigrationSettingsResponse(
        boolean migrationEnabled,
        String defaultConfidentialityCode,
        String updatedAt,
        String updatedBy,
        /** True while the value still comes from the environment, i.e. never saved from the UI. */
        boolean environmentDefault) {
}
