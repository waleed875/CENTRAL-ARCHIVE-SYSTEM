package com.centralArchiveSystem.Archive.import_.migration.setting;

import com.centralArchiveSystem.Archive.import_.migration.setting.dto.MigrationSettingsResponse;
import com.centralArchiveSystem.Archive.import_.migration.setting.dto.MigrationSettingsUpdateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and writes the runtime-editable migration switch.
 *
 * <p>The environment supplies the value until an administrator saves from the UI; from that point
 * the stored value wins. Callers use {@link #isMigrationEnabled()} at the moment the value is needed
 * rather than caching it in a constructor, so opening or closing the migration window takes effect
 * without a restart.
 *
 * <p>This used to also carry a run-wide default confidentiality code stamped onto every migrated
 * file. It was removed because a file classified more strictly than its own project is dropped from
 * the file listing by anyone whose clearance does not reach it, silently: the project opened to an
 * empty list with no error to explain it. Migrated files now inherit their project's level, so
 * classification is decided once, on the project, and cannot disagree with its own contents.
 */
@Service
public class MigrationSettingsService {

    private static final Logger log = LoggerFactory.getLogger(MigrationSettingsService.class);

    private final MigrationSettingsRepository repository;
    private final boolean environmentEnabled;

    public MigrationSettingsService(MigrationSettingsRepository repository,
                                    @Value("${legacy.migration.enabled:false}") boolean environmentEnabled) {
        this.repository = repository;
        this.environmentEnabled = environmentEnabled;
    }

    /**
     * Read on every migration attempt. If the row cannot be read the environment value is used, so a
     * settings outage can never silently open the migration window on its own.
     */
    @Transactional(readOnly = true)
    public boolean isMigrationEnabled() {
        try {
            return load().filter(MigrationSettings::isConfigured)
                    .map(s -> Boolean.TRUE.equals(s.getMigrationEnabled()))
                    .orElse(environmentEnabled);
        } catch (Exception e) {
            log.warn("Falling back to LEGACY_MIGRATION_ENABLED because migration settings are unreadable", e);
            return environmentEnabled;
        }
    }

    @Transactional(readOnly = true)
    public MigrationSettingsResponse getSettings() {
        return toResponse(load());
    }

    @Transactional
    public MigrationSettingsResponse updateSettings(MigrationSettingsUpdateRequest request, UUID actingUserId) {
        MigrationSettings settings = load().orElseGet(() -> {
            MigrationSettings fresh = new MigrationSettings();
            fresh.setId(UUID.randomUUID());
            fresh.setSingleton(Boolean.TRUE);
            return fresh;
        });

        if (request.migrationEnabled() != null) {
            settings.setMigrationEnabled(request.migrationEnabled());
        }
        settings.setConfigured(Boolean.TRUE);
        settings.setUpdatedAt(LocalDateTime.now());
        settings.setUpdatedBy(actingUserId);

        MigrationSettings saved = repository.save(settings);
        log.info("Legacy migration settings updated by {}: enabled={}", actingUserId, saved.getMigrationEnabled());
        // Answered from the saved row rather than re-reading, so the response always reflects this write.
        return toResponse(Optional.of(saved));
    }

    private MigrationSettingsResponse toResponse(Optional<MigrationSettings> stored) {
        boolean usingEnvironment = stored.filter(MigrationSettings::isConfigured).isEmpty();
        MigrationSettings row = stored.orElse(null);

        boolean enabled = usingEnvironment
                ? environmentEnabled
                : Boolean.TRUE.equals(row.getMigrationEnabled());

        return new MigrationSettingsResponse(
                enabled,
                row == null || row.getUpdatedAt() == null ? null : row.getUpdatedAt().toString(),
                row == null || row.getUpdatedBy() == null ? null : row.getUpdatedBy().toString(),
                usingEnvironment);
    }

    private Optional<MigrationSettings> load() {
        return repository.findFirstBySingletonTrue();
    }
}
