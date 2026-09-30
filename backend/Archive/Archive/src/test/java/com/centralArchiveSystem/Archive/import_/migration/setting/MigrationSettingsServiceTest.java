package com.centralArchiveSystem.Archive.import_.migration.setting;

import com.centralArchiveSystem.Archive.import_.migration.setting.dto.MigrationSettingsResponse;
import com.centralArchiveSystem.Archive.import_.migration.setting.dto.MigrationSettingsUpdateRequest;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The migration switch is the only value this screen owns. It used to also carry a run-wide default
 * confidentiality code; that is gone, and these tests pin the remaining environment-over-store
 * precedence, which is the part that is easy to break silently.
 */
class MigrationSettingsServiceTest {

    private final MigrationSettingsRepository repository = mock(MigrationSettingsRepository.class);

    private MigrationSettingsService serviceWithEnvironment(boolean enabled) {
        return new MigrationSettingsService(repository, enabled);
    }

    private MigrationSettings storedRow(boolean configured, boolean enabled) {
        MigrationSettings row = new MigrationSettings();
        row.setId(UUID.randomUUID());
        row.setSingleton(Boolean.TRUE);
        row.setConfigured(configured);
        row.setMigrationEnabled(enabled);
        row.setUpdatedAt(java.time.LocalDateTime.of(2026, 9, 27, 10, 0));
        row.setUpdatedBy(UUID.randomUUID());
        return row;
    }

    @Test
    void unconfiguredRowKeepsUsingTheEnvironmentValue() {
        when(repository.findFirstBySingletonTrue())
                .thenReturn(Optional.of(storedRow(false, true)));
        MigrationSettingsService service = serviceWithEnvironment(false);

        // The stored row says "enabled" but it was never written from the UI, so the environment wins.
        assertFalse(service.isMigrationEnabled());
    }

    @Test
    void missingRowIsTreatedAsEnvironmentDefault() {
        when(repository.findFirstBySingletonTrue()).thenReturn(Optional.empty());
        MigrationSettingsService service = serviceWithEnvironment(true);

        assertTrue(service.isMigrationEnabled());
        assertTrue(service.getSettings().environmentDefault());
    }

    @Test
    void savedValuesOverrideTheEnvironmentWithoutRestart() {
        when(repository.findFirstBySingletonTrue())
                .thenReturn(Optional.of(storedRow(true, true)));
        MigrationSettingsService service = serviceWithEnvironment(false);

        // Same service instance, value now comes from the row: this is the no-restart requirement.
        assertTrue(service.isMigrationEnabled());
    }

    @Test
    void unreadableSettingsFallBackToTheEnvironmentSoTheWindowCannotOpenSilently() {
        when(repository.findFirstBySingletonTrue()).thenThrow(new RuntimeException("connection reset"));
        MigrationSettingsService service = serviceWithEnvironment(false);

        assertFalse(service.isMigrationEnabled());
    }

    @Test
    void aFailingReadMustNeverOpenTheMigrationWindowOnItsOwn() {
        when(repository.findFirstBySingletonTrue()).thenThrow(new RuntimeException("connection reset"));

        // The environment says enabled. A read failure must not be read as consent to migrate.
        assertTrue(serviceWithEnvironment(true).isMigrationEnabled());
    }

    @Test
    void updateStoresTheSwitchAndMarksTheRowConfigured() {
        when(repository.findFirstBySingletonTrue()).thenReturn(Optional.empty());
        when(repository.save(any(MigrationSettings.class))).thenAnswer(inv -> {
            MigrationSettings saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
        MigrationSettingsService service = serviceWithEnvironment(false);

        UUID actingUser = UUID.randomUUID();
        MigrationSettingsResponse response = service.updateSettings(
                new MigrationSettingsUpdateRequest(true), actingUser);

        assertTrue(response.migrationEnabled());
        assertFalse(response.environmentDefault());
        assertEquals(actingUser.toString(), response.updatedBy());

        var captor = org.mockito.ArgumentCaptor.forClass(MigrationSettings.class);
        verify(repository).save(captor.capture());
        MigrationSettings saved = captor.getValue();
        assertTrue(saved.isConfigured());
        assertTrue(saved.getMigrationEnabled());
        assertNotNull(saved.getUpdatedAt());
        assertEquals(actingUser, saved.getUpdatedBy());
    }

    @Test
    void theResponseReportsTheJustWrittenValueRatherThanReReading() {
        // A stateful stub: the row has to come back from the repository for the second read to mean
        // anything, otherwise the service correctly falls through to the environment and the
        // assertion would pass or fail for the wrong reason.
        AtomicReference<MigrationSettings> stored = new AtomicReference<>();
        when(repository.findFirstBySingletonTrue())
                .thenAnswer(inv -> Optional.ofNullable(stored.get()));
        when(repository.save(any(MigrationSettings.class))).thenAnswer(inv -> {
            stored.set(inv.getArgument(0));
            return stored.get();
        });
        MigrationSettingsService service = serviceWithEnvironment(true);

        // Closing the window must read back as closed even though the environment still says enabled.
        MigrationSettingsResponse response = service.updateSettings(
                new MigrationSettingsUpdateRequest(false), UUID.randomUUID());

        assertFalse(response.migrationEnabled());
        assertFalse(response.environmentDefault());
        assertFalse(service.isMigrationEnabled());
    }

    @Test
    void aNullFieldInTheUpdateLeavesTheStoredValueAlone() {
        when(repository.findFirstBySingletonTrue())
                .thenReturn(Optional.of(storedRow(true, true)));
        when(repository.save(any(MigrationSettings.class))).thenAnswer(inv -> inv.getArgument(0));
        MigrationSettingsService service = serviceWithEnvironment(false);

        service.updateSettings(new MigrationSettingsUpdateRequest(null), UUID.randomUUID());

        assertTrue(service.isMigrationEnabled());
    }
}
