package com.centralArchiveSystem.Archive.import_.migration.setting.dto;

/**
 * Partial update of {@link MigrationSettingsResponse}: a null field is left untouched, so the UI can
 * flip the switch without having to resend anything else.
 */
public record MigrationSettingsUpdateRequest(
        Boolean migrationEnabled) {
}
