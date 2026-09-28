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
 * Reads and writes the runtime-editable migration switches.
 *
 * <p>The environment supplies the value until an administrator saves from the UI; from that point
 * the stored value wins. Callers use {@link #isMigrationEnabled()} and
 * {@link #resolveConfidentialityCode()} at the moment the value is needed rather than caching it in a
 * constructor, so opening or closing the migration window takes effect without a restart.
 */
@Service
public class MigrationSettingsService {

    private static final Logger log = LoggerFactory.getLogger(MigrationSettingsService.class);

    private final MigrationSettingsRepository repository;
    private final SettingsCrypto crypto;
    private final boolean environmentEnabled;
    private final String environmentConfidentialityCode;

    public MigrationSettingsService(MigrationSettingsRepository repository,
                                    SettingsCrypto crypto,
                                    @Value("${legacy.migration.enabled:false}") boolean environmentEnabled,
                                    @Value("${legacy.migration.default-confidentiality-code:}") String environmentConfidentialityCode) {
        this.repository = repository;
        this.crypto = crypto;
        this.environmentEnabled = environmentEnabled;
        this.environmentConfidentialityCode = blankToEmpty(environmentConfidentialityCode);
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

    /**
     * Read on every migration attempt. Once the UI has saved, a blank stored code means "explicitly
     * cleared" and returns null rather than quietly falling back to the environment.
     */
    @Transactional(readOnly = true)
    public String resolveConfidentialityCode() {
        try {
            Optional<MigrationSettings> settings = load().filter(MigrationSettings::isConfigured);
            if (settings.isPresent()) {
                return decryptOrNull(settings.get().getDefaultConfidentialityCodeEncrypted());
            }
        } catch (Exception e) {
            log.warn("Falling back to LEGACY_MIGRATION_DEFAULT_CONFIDENTIALITY_CODE", e);
        }
        return environmentConfidentialityCode.isEmpty() ? null : environmentConfidentialityCode;
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
        if (request.defaultConfidentialityCode() != null) {
            // An empty string is a real instruction to clear the code, so it encrypts to null and
            // stops overriding the environment from the next run onwards.
            settings.setDefaultConfidentialityCodeEncrypted(crypto.encrypt(request.defaultConfidentialityCode().trim()));
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

        String code = usingEnvironment
                ? nullIfEmpty(environmentConfidentialityCode)
                : decryptOrNull(row.getDefaultConfidentialityCodeEncrypted());

        return new MigrationSettingsResponse(
                enabled,
                code,
                row == null || row.getUpdatedAt() == null ? null : row.getUpdatedAt().toString(),
                row == null || row.getUpdatedBy() == null ? null : row.getUpdatedBy().toString(),
                usingEnvironment);
    }

    private Optional<MigrationSettings> load() {
        return repository.findFirstBySingletonTrue();
    }

    private String decryptOrNull(String envelope) {
        try {
            String plain = crypto.decrypt(envelope);
            return plain == null || plain.isBlank() ? null : plain;
        } catch (RuntimeException e) {
            log.warn("Stored confidentiality code cannot be decrypted; treating it as unset", e);
            return null;
        }
    }

    private static String blankToEmpty(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    private static String nullIfEmpty(String value) {
        return value.isEmpty() ? null : value;
    }
}
