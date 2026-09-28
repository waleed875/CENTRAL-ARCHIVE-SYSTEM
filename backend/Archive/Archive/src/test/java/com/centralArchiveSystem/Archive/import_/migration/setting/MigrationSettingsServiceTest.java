package com.centralArchiveSystem.Archive.import_.migration.setting;

import com.centralArchiveSystem.Archive.import_.migration.setting.dto.MigrationSettingsResponse;
import com.centralArchiveSystem.Archive.import_.migration.setting.dto.MigrationSettingsUpdateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MigrationSettingsServiceTest {

    private static final String SECRET = "test-encryption-key-value";

    private MigrationSettingsRepository repository;
    private MigrationSettingsService service;
    private SettingsCrypto crypto;

    @BeforeEach
    void setUp() {
        repository = mock(MigrationSettingsRepository.class);
        crypto = new SettingsCrypto(SECRET);
    }

    private MigrationSettingsService serviceWithEnvironment(boolean enabled, String code) {
        return new MigrationSettingsService(repository, crypto, enabled, code);
    }

    private MigrationSettings storedRow(boolean configured, boolean enabled, String encryptedCode) {
        MigrationSettings row = new MigrationSettings();
        row.setId(UUID.randomUUID());
        row.setSingleton(Boolean.TRUE);
        row.setConfigured(configured);
        row.setMigrationEnabled(enabled);
        row.setDefaultConfidentialityCodeEncrypted(encryptedCode);
        row.setUpdatedAt(java.time.LocalDateTime.of(2026, 9, 27, 10, 0));
        row.setUpdatedBy(UUID.randomUUID());
        return row;
    }

    @Test
    void unconfiguredRowKeepsUsingTheEnvironmentValue() {
        when(repository.findFirstBySingletonTrue())
                .thenReturn(Optional.of(storedRow(false, true, null)));
        service = serviceWithEnvironment(false, "ENV_CODE");

        // The stored row says "enabled" but it was never written from the UI, so the environment wins.
        assertFalse(service.isMigrationEnabled());
        assertEquals("ENV_CODE", service.resolveConfidentialityCode());
    }

    @Test
    void missingRowIsTreatedAsEnvironmentDefault() {
        when(repository.findFirstBySingletonTrue()).thenReturn(Optional.empty());
        service = serviceWithEnvironment(true, "ENV_CODE");

        assertTrue(service.isMigrationEnabled());
        assertEquals("ENV_CODE", service.resolveConfidentialityCode());
        assertTrue(service.getSettings().environmentDefault());
    }

    @Test
    void savedValuesOverrideTheEnvironmentWithoutRestart() {
        when(repository.findFirstBySingletonTrue())
                .thenReturn(Optional.of(storedRow(true, true, crypto.encrypt("SAVED_CODE"))));
        service = serviceWithEnvironment(false, "ENV_CODE");

        // Same service instance, values now come from the row: this is the no-restart requirement.
        assertTrue(service.isMigrationEnabled());
        assertEquals("SAVED_CODE", service.resolveConfidentialityCode());
    }

    @Test
    void unreadableSettingsFallBackToTheEnvironmentSoTheWindowCannotOpenSilently() {
        when(repository.findFirstBySingletonTrue()).thenThrow(new RuntimeException("connection reset"));
        service = serviceWithEnvironment(false, "ENV_CODE");

        assertFalse(service.isMigrationEnabled());
        assertEquals("ENV_CODE", service.resolveConfidentialityCode());
    }

    @Test
    void anUndecryptableCodeIsReportedAsUnsetRatherThanBreakingTheResponse() {
        when(repository.findFirstBySingletonTrue())
                .thenReturn(Optional.of(storedRow(true, true, "v1:AAAAAAAAAAAAAAAA:Zm9v")));
        service = serviceWithEnvironment(false, "ENV_CODE");

        // The environment must not silently take over once the UI has taken ownership.
        assertNull(service.resolveConfidentialityCode());
        assertNull(service.getSettings().defaultConfidentialityCode());
    }

    @Test
    void updateEncryptsTheCodeAndMarksTheRowConfigured() {
        when(repository.findFirstBySingletonTrue()).thenReturn(Optional.empty());
        when(repository.save(any(MigrationSettings.class))).thenAnswer(inv -> {
            MigrationSettings saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
        service = serviceWithEnvironment(false, "");

        UUID actingUser = UUID.randomUUID();
        MigrationSettingsResponse response = service.updateSettings(
                new MigrationSettingsUpdateRequest(true, "SAVED_CODE"), actingUser);

        assertTrue(response.migrationEnabled());
        assertEquals("SAVED_CODE", response.defaultConfidentialityCode());
        assertFalse(response.environmentDefault());
        assertEquals(actingUser.toString(), response.updatedBy());

        var captor = org.mockito.ArgumentCaptor.forClass(MigrationSettings.class);
        verify(repository).save(captor.capture());
        MigrationSettings saved = captor.getValue();
        assertTrue(saved.isConfigured());
        assertNotNull(saved.getUpdatedAt());
        assertEquals(actingUser, saved.getUpdatedBy());
        // At rest the value must be the envelope, never the code itself.
        assertNotEquals("SAVED_CODE", saved.getDefaultConfidentialityCodeEncrypted());
        assertTrue(saved.getDefaultConfidentialityCodeEncrypted().startsWith("v1:"));
    }

    @Test
    void clearingTheCodeIsAnExplicitInstructionAndStopsTheEnvironmentFromTakingOver() {
        when(repository.findFirstBySingletonTrue())
                .thenReturn(Optional.of(storedRow(true, true, crypto.encrypt("OLD_CODE"))));
        when(repository.save(any(MigrationSettings.class))).thenAnswer(inv -> inv.getArgument(0));
        service = serviceWithEnvironment(true, "ENV_CODE");

        service.updateSettings(new MigrationSettingsUpdateRequest(null, "  "), UUID.randomUUID());

        assertNull(service.resolveConfidentialityCode());
    }

    @Test
    void aNullFieldInTheUpdateLeavesTheStoredValueAlone() {
        when(repository.findFirstBySingletonTrue())
                .thenReturn(Optional.of(storedRow(true, false, crypto.encrypt("KEPT_CODE"))));
        when(repository.save(any(MigrationSettings.class))).thenAnswer(inv -> inv.getArgument(0));
        service = serviceWithEnvironment(false, "ENV_CODE");

        // Toggling the switch must not wipe the confidentiality code the UI never re-sent.
        service.updateSettings(new MigrationSettingsUpdateRequest(true, null), UUID.randomUUID());

        assertTrue(service.isMigrationEnabled());
        assertEquals("KEPT_CODE", service.resolveConfidentialityCode());
    }
}
