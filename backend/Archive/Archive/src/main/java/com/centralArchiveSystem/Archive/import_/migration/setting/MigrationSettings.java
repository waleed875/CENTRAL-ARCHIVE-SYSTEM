package com.centralArchiveSystem.Archive.import_.migration.setting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Singleton row holding the two legacy migration switches that the UI may change at runtime.
 *
 * <p>The environment variables remain the source of truth for the initial value; once an
 * administrator saves from the UI the stored value wins, which is why the callers fall back to the
 * environment when this table has never been written.
 */
@Entity
@Table(name = "migration_settings", schema = "import")
public class MigrationSettings {

    @Id
    private UUID id;

    @Column(name = "singleton", nullable = false)
    private Boolean singleton = Boolean.TRUE;

    /**
     * False while the environment is still the source of truth. Set on the first UI save, which is
     * what makes it possible to tell "never configured" apart from "configured to false".
     */
    @Column(name = "configured", nullable = false)
    private Boolean configured = Boolean.FALSE;

    @Column(name = "migration_enabled", nullable = false)
    private Boolean migrationEnabled = Boolean.FALSE;

    /** AES-GCM envelope produced by {@link SettingsCrypto}; never a clear text code. */
    @Column(name = "default_confidentiality_code_encrypted", columnDefinition = "TEXT")
    private String defaultConfidentialityCodeEncrypted;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Version
    private Long version;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Boolean getSingleton() {
        return singleton;
    }

    public void setSingleton(Boolean singleton) {
        this.singleton = singleton;
    }

    public Boolean getConfigured() {
        return configured;
    }

    public void setConfigured(Boolean configured) {
        this.configured = configured;
    }

    public boolean isConfigured() {
        return Boolean.TRUE.equals(configured);
    }

    public Boolean getMigrationEnabled() {
        return migrationEnabled;
    }

    public void setMigrationEnabled(Boolean migrationEnabled) {
        this.migrationEnabled = migrationEnabled;
    }

    public String getDefaultConfidentialityCodeEncrypted() {
        return defaultConfidentialityCodeEncrypted;
    }

    public void setDefaultConfidentialityCodeEncrypted(String defaultConfidentialityCodeEncrypted) {
        this.defaultConfidentialityCodeEncrypted = defaultConfidentialityCodeEncrypted;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(UUID updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
