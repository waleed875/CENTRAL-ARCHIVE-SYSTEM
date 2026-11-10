package com.centralArchiveSystem.Archive.import_.migration;

import com.centralArchiveSystem.Archive.import_.migration.dto.MigrationBatchCompleteRequest;
import com.centralArchiveSystem.Archive.import_.migration.dto.MigrationRunRequest;
import com.centralArchiveSystem.Archive.import_.migration.dto.MigrationSourceRequest;
import com.centralArchiveSystem.Archive.import_.migration.dto.MigrationSourceResponse;
import com.centralArchiveSystem.Archive.import_.migration.dto.MigrationSourceUpdateRequest;
import com.centralArchiveSystem.Archive.import_.migration.legacy.LegacyMigrationItemRepository;
import com.centralArchiveSystem.Archive.import_.migration.setting.MigrationSettingsService;
import com.centralArchiveSystem.Archive.import_.migration.setting.SettingsCrypto;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MigrationServiceTest {

    /**
     * The enabled flag now comes from the runtime-editable settings table rather than a constructor
     * argument, so each test states explicitly whether the migration window is open.
     */
    private static MigrationSettingsService migrationSettings(boolean enabled) {
        MigrationSettingsService settings = mock(MigrationSettingsService.class);
        when(settings.isMigrationEnabled()).thenReturn(enabled);
        return settings;
    }

    /** Real instance: it only derives a key from a secret, so it needs no Spring context. */
    private static SettingsCrypto crypto() {
        return new SettingsCrypto("test-encryption-key-for-migration-sources");
    }

    @Test
    void runBatchMovesPlannedBatchToRunningAndDispatchesExecution() {
        MigrationSourceRepository sources = mock(MigrationSourceRepository.class);
        MigrationBatchRepository batches = mock(MigrationBatchRepository.class);
        MigrationExecutionService execution = mock(MigrationExecutionService.class);

        UUID id = UUID.randomUUID();
        MigrationBatch batch = new MigrationBatch();
        batch.setId(id);
        batch.setMigrationSourceId(UUID.randomUUID());
        batch.setBatchNumber("B-001");
        batch.setStatus(MigrationBatch.Status.PLANNED);

        MigrationSource source = new MigrationSource();
        source.setId(batch.getMigrationSourceId());
        source.setSourceType(MigrationSourceType.POSTGRESQL);
        source.setOfficeName("ARC2");

        when(batches.findById(id)).thenReturn(Optional.of(batch));
        when(batches.saveAndFlush(batch)).thenReturn(batch);
        when(sources.findById(batch.getMigrationSourceId())).thenReturn(Optional.of(source));

        MigrationService service = new MigrationService(sources, batches, execution, migrationSettings(true), crypto(), mock(LegacyMigrationItemRepository.class));

        var response = service.runBatch(
                id,
                new MigrationRunRequest(
                        new MigrationRunRequest.MigrationConfig(
                                null,
                                null,
                                Map.of(),
                                null
                        )
                )
        );

        assertEquals(MigrationBatch.Status.RUNNING.name(), response.status());
        assertNotNull(batch.getStartedAt());
        assertNull(batch.getCompletedAt());
        verify(batches).saveAndFlush(batch);
        verify(execution).execute(id);
    }

    @Test
    void runBatchRejectsSuccessfulBatch() {
        MigrationSourceRepository sources = mock(MigrationSourceRepository.class);
        MigrationBatchRepository batches = mock(MigrationBatchRepository.class);
        MigrationExecutionService execution = mock(MigrationExecutionService.class);

        UUID id = UUID.randomUUID();
        MigrationBatch batch = new MigrationBatch();
        batch.setId(id);
        batch.setMigrationSourceId(UUID.randomUUID());
        batch.setBatchNumber("B-002");
        batch.setStatus(MigrationBatch.Status.SUCCESS);

        MigrationSource source = new MigrationSource();
        source.setId(batch.getMigrationSourceId());
        source.setSourceType(MigrationSourceType.POSTGRESQL);
        source.setOfficeName("ARC2");

        when(batches.findById(id)).thenReturn(Optional.of(batch));
        when(sources.findById(batch.getMigrationSourceId())).thenReturn(Optional.of(source));

        MigrationService service = new MigrationService(sources, batches, execution, migrationSettings(true), crypto(), mock(LegacyMigrationItemRepository.class));

        assertThrows(
                IllegalStateException.class,
                () -> service.runBatch(
                        id,
                        new MigrationRunRequest(
                                new MigrationRunRequest.MigrationConfig(
                                        null,
                                        null,
                                        Map.of(),
                                        null
                                )
                        )
                )
        );

        verify(execution, never()).execute(any());
        verify(batches, never()).save(any());
    }


    @Test
    void runBatchRejectsExecutionWhenLegacyMigrationIsDisabled() {
        MigrationSourceRepository sources = mock(MigrationSourceRepository.class);
        MigrationBatchRepository batches = mock(MigrationBatchRepository.class);
        MigrationExecutionService execution = mock(MigrationExecutionService.class);

        UUID id = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();

        MigrationBatch batch = new MigrationBatch();
        batch.setId(id);
        batch.setMigrationSourceId(sourceId);
        batch.setBatchNumber("B-DISABLED");
        batch.setStatus(MigrationBatch.Status.PLANNED);

        when(batches.findById(id)).thenReturn(Optional.of(batch));

        MigrationService service = new MigrationService(
                sources, batches, execution, migrationSettings(false), crypto(), mock(LegacyMigrationItemRepository.class));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.runBatch(
                        id,
                        new MigrationRunRequest(
                                new MigrationRunRequest.MigrationConfig(
                                        null,
                                        null,
                                        Map.of(),
                                        null
                                )
                        )
                )
        );

        assertTrue(error.getMessage().contains("disabled"));
        assertEquals(MigrationBatch.Status.PLANNED, batch.getStatus());
        verify(sources, never()).findById(any());
        verify(batches, never()).save(any());
        verify(execution, never()).execute(any());
    }

    @Test
    void runBatchRejectsWhenAnotherBatchIsAlreadyRunning() {
        MigrationSourceRepository sources = mock(MigrationSourceRepository.class);
        MigrationBatchRepository batches = mock(MigrationBatchRepository.class);
        MigrationExecutionService execution = mock(MigrationExecutionService.class);

        UUID id = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();

        MigrationBatch batch = new MigrationBatch();
        batch.setId(id);
        batch.setMigrationSourceId(sourceId);
        batch.setBatchNumber("B-CONCURRENT");
        batch.setStatus(MigrationBatch.Status.PLANNED);

        MigrationSource source = new MigrationSource();
        source.setId(sourceId);
        source.setSourceType(MigrationSourceType.POSTGRESQL);
        source.setOfficeName("ARC2");

        when(batches.findById(id)).thenReturn(Optional.of(batch));
        when(sources.findById(sourceId)).thenReturn(Optional.of(source));
        when(batches.existsByStatus(MigrationBatch.Status.RUNNING)).thenReturn(true);

        MigrationService service = new MigrationService(
                sources, batches, execution, migrationSettings(true), crypto(), mock(LegacyMigrationItemRepository.class));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.runBatch(
                        id,
                        new MigrationRunRequest(
                                new MigrationRunRequest.MigrationConfig(
                                        null,
                                        null,
                                        Map.of(),
                                        null
                                )
                        )
                )
        );

        assertTrue(error.getMessage().contains("already RUNNING"));
        assertEquals(MigrationBatch.Status.PLANNED, batch.getStatus());
        verify(batches, never()).saveAndFlush(any());
        verify(execution, never()).execute(any());
    }

    @Test
    void runBatchRejectsDynamicConnectionInsteadOfIgnoringIt() {
        MigrationSourceRepository sources = mock(MigrationSourceRepository.class);
        MigrationBatchRepository batches = mock(MigrationBatchRepository.class);
        MigrationExecutionService execution = mock(MigrationExecutionService.class);

        UUID id = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();

        MigrationBatch batch = new MigrationBatch();
        batch.setId(id);
        batch.setMigrationSourceId(sourceId);
        batch.setBatchNumber("B-DYNAMIC");
        batch.setStatus(MigrationBatch.Status.PLANNED);

        MigrationSource source = new MigrationSource();
        source.setId(sourceId);
        source.setSourceType(MigrationSourceType.POSTGRESQL);
        source.setOfficeName("ARC2");

        when(batches.findById(id)).thenReturn(Optional.of(batch));
        when(sources.findById(sourceId)).thenReturn(Optional.of(source));

        MigrationService service = new MigrationService(
                sources, batches, execution, migrationSettings(true), crypto(), mock(LegacyMigrationItemRepository.class));

        assertThrows(
                IllegalArgumentException.class,
                () -> service.runBatch(
                        id,
                        new MigrationRunRequest(
                                new MigrationRunRequest.MigrationConfig(
                                        "jdbc:postgresql://another-host/db",
                                        null,
                                        Map.of(),
                                        null
                                )
                        )
                )
        );

        verify(execution, never()).execute(any());
        verify(batches, never()).save(any());
    }

    @Test
    void runBatchRejectsUnimplementedFileAndMappingAdapters() {
        MigrationSourceRepository sources = mock(MigrationSourceRepository.class);
        MigrationBatchRepository batches = mock(MigrationBatchRepository.class);
        MigrationExecutionService execution = mock(MigrationExecutionService.class);

        UUID id = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();

        MigrationBatch batch = new MigrationBatch();
        batch.setId(id);
        batch.setMigrationSourceId(sourceId);
        batch.setBatchNumber("B-FILE");
        batch.setStatus(MigrationBatch.Status.PLANNED);

        MigrationSource source = new MigrationSource();
        source.setId(sourceId);
        source.setSourceType(MigrationSourceType.POSTGRESQL);
        source.setOfficeName("ARC2");

        when(batches.findById(id)).thenReturn(Optional.of(batch));
        when(sources.findById(sourceId)).thenReturn(Optional.of(source));

        MigrationService service = new MigrationService(
                sources, batches, execution, migrationSettings(true), crypto(), mock(LegacyMigrationItemRepository.class));

        assertThrows(
                IllegalArgumentException.class,
                () -> service.runBatch(
                        id,
                        new MigrationRunRequest(
                                new MigrationRunRequest.MigrationConfig(
                                        null,
                                        "/tmp/legacy.csv",
                                        Map.of(),
                                        null
                                )
                        )
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.runBatch(
                        id,
                        new MigrationRunRequest(
                                new MigrationRunRequest.MigrationConfig(
                                        null,
                                        null,
                                        Map.of("old_type", UUID.randomUUID()),
                                        null
                                )
                        )
                )
        );

        verify(execution, never()).execute(any());
        verify(batches, never()).save(any());
    }

    @Test
    void runBatchRejectsUnsupportedConfidentialityOverride() {
        MigrationSourceRepository sources = mock(MigrationSourceRepository.class);
        MigrationBatchRepository batches = mock(MigrationBatchRepository.class);
        MigrationExecutionService execution = mock(MigrationExecutionService.class);

        UUID id = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();

        MigrationBatch batch = new MigrationBatch();
        batch.setId(id);
        batch.setMigrationSourceId(sourceId);
        batch.setBatchNumber("B-CONF");
        batch.setStatus(MigrationBatch.Status.PLANNED);

        MigrationSource source = new MigrationSource();
        source.setId(sourceId);
        source.setSourceType(MigrationSourceType.POSTGRESQL);
        source.setOfficeName("ARC2");

        when(batches.findById(id)).thenReturn(Optional.of(batch));
        when(sources.findById(sourceId)).thenReturn(Optional.of(source));

        MigrationService service = new MigrationService(
                sources, batches, execution, migrationSettings(true), crypto(), mock(LegacyMigrationItemRepository.class));

        assertThrows(
                IllegalArgumentException.class,
                () -> service.runBatch(
                        id,
                        new MigrationRunRequest(
                                new MigrationRunRequest.MigrationConfig(
                                        null,
                                        null,
                                        Map.of(),
                                        UUID.randomUUID()
                                )
                        )
                )
        );

        verify(execution, never()).execute(any());
        verify(batches, never()).save(any());
    }

    @Test
    void deleteSourceRejectsRunningBatches() {
        MigrationSourceRepository sources = mock(MigrationSourceRepository.class);
        MigrationBatchRepository batches = mock(MigrationBatchRepository.class);
        MigrationExecutionService execution = mock(MigrationExecutionService.class);

        UUID sourceId = UUID.randomUUID();
        MigrationSource source = new MigrationSource();
        source.setId(sourceId);
        source.setIsActive(true);
        when(sources.findById(sourceId)).thenReturn(Optional.of(source));
        when(batches.countByMigrationSourceIdAndStatus(
                sourceId,
                MigrationBatch.Status.RUNNING
        )).thenReturn(1L);

        MigrationService service = new MigrationService(sources, batches, execution, migrationSettings(true), crypto(), mock(LegacyMigrationItemRepository.class));

        assertThrows(IllegalStateException.class, () -> service.deleteSource(sourceId));
        verify(sources, never()).save(any());
    }

    @Test
    void deleteSourcePreservesCompletedBatchHistory() {
        MigrationSourceRepository sources = mock(MigrationSourceRepository.class);
        MigrationBatchRepository batches = mock(MigrationBatchRepository.class);
        MigrationExecutionService execution = mock(MigrationExecutionService.class);

        UUID sourceId = UUID.randomUUID();
        MigrationSource source = new MigrationSource();
        source.setId(sourceId);
        source.setIsActive(true);
        when(sources.findById(sourceId)).thenReturn(Optional.of(source));
        when(batches.countByMigrationSourceIdAndStatus(
                sourceId,
                MigrationBatch.Status.RUNNING
        )).thenReturn(0L);
        when(batches.countByMigrationSourceId(sourceId)).thenReturn(2L);
        when(sources.save(any(MigrationSource.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MigrationService service = new MigrationService(sources, batches, execution, migrationSettings(true), crypto(), mock(LegacyMigrationItemRepository.class));

        service.deleteSource(sourceId);
        assertFalse(source.getIsActive(), "source should be removed from active use");
        verify(sources).save(source);
        verify(sources, never()).deleteById(any());
    }

    @Test
    void completeBatchAcceptsOnlyTerminalStatuses() {
        MigrationSourceRepository sources = mock(MigrationSourceRepository.class);
        MigrationBatchRepository batches = mock(MigrationBatchRepository.class);
        MigrationExecutionService execution = mock(MigrationExecutionService.class);

        UUID id = UUID.randomUUID();
        MigrationBatch batch = new MigrationBatch();
        batch.setId(id);
        batch.setMigrationSourceId(UUID.randomUUID());
        batch.setBatchNumber("B-003");
        batch.setStatus(MigrationBatch.Status.RUNNING);

        when(batches.findById(id)).thenReturn(Optional.of(batch));

        MigrationService service = new MigrationService(sources, batches, execution, migrationSettings(true), crypto(), mock(LegacyMigrationItemRepository.class));

        assertThrows(
                IllegalArgumentException.class,
                () -> service.completeBatch(
                        id,
                        new MigrationBatchCompleteRequest(
                                MigrationBatch.Status.RUNNING,
                                "not terminal"
                        )
                )
        );
        verify(batches, never()).save(any());
    }

    @Test
    void createSourceEncryptsThePasswordAndNeverReturnsIt() {
        MigrationSourceRepository sources = mock(MigrationSourceRepository.class);
        MigrationBatchRepository batches = mock(MigrationBatchRepository.class);
        MigrationExecutionService execution = mock(MigrationExecutionService.class);

        when(sources.existsByOfficeName("ARC1")).thenReturn(false);
        when(sources.save(any(MigrationSource.class))).thenAnswer(invocation -> {
            MigrationSource source = invocation.getArgument(0);
            source.setId(UUID.randomUUID());
            return source;
        });

        MigrationService service = new MigrationService(sources, batches, execution, migrationSettings(true), crypto(), mock(LegacyMigrationItemRepository.class));

        var response = service.createSource(new MigrationSourceRequest(
                "Office A", MigrationSourceType.POSTGRESQL, "arc1_db", "ARC1",
                "10.0.0.5", 5432, "arc1_reader", "s3cret", "first office"));

        ArgumentCaptor<MigrationSource> captor = ArgumentCaptor.forClass(MigrationSource.class);
        verify(sources).save(captor.capture());
        MigrationSource stored = captor.getValue();

        assertTrue(stored.hasStoredConnection());
        assertNotEquals("s3cret", stored.getPasswordEncrypted(), "the password must be stored encrypted");
        assertTrue(stored.getPasswordEncrypted().contains("s3cret") == false);

        // The response may describe the connection but must not carry the secret at all.
        assertTrue(response.hasStoredConnection());
        assertEquals("10.0.0.5", response.host());
        assertEquals(5432, response.port());
        assertEquals("arc1_reader", response.username());
        assertTrue(Arrays.stream(MigrationSourceResponse.class.getRecordComponents())
                        .noneMatch(component -> component.getName().toLowerCase().contains("password")),
                "the source response must not expose any password field");
    }

    @Test
    void createSourceRejectsAHalfFilledConnection() {
        MigrationSourceRepository sources = mock(MigrationSourceRepository.class);
        MigrationBatchRepository batches = mock(MigrationBatchRepository.class);
        MigrationExecutionService execution = mock(MigrationExecutionService.class);

        when(sources.existsByOfficeName("ARC1")).thenReturn(false);

        MigrationService service = new MigrationService(sources, batches, execution, migrationSettings(true), crypto(), mock(LegacyMigrationItemRepository.class));

        // A host and password but no username or port would build an unusable JDBC URL later on.
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                service.createSource(new MigrationSourceRequest(
                        "Office A", MigrationSourceType.POSTGRESQL, "arc1_db", "ARC1",
                        "10.0.0.5", null, null, "s3cret", null)));

        assertTrue(error.getMessage().contains("port"));
        assertTrue(error.getMessage().contains("username"));
        verify(sources, never()).save(any());
    }

    @Test
    void createSourceAllowsNoConnectionAtAllToUseTheEnvironmentDefault() {
        MigrationSourceRepository sources = mock(MigrationSourceRepository.class);
        MigrationBatchRepository batches = mock(MigrationBatchRepository.class);
        MigrationExecutionService execution = mock(MigrationExecutionService.class);

        when(sources.existsByOfficeName("ARC2")).thenReturn(false);
        when(sources.save(any(MigrationSource.class))).thenAnswer(invocation -> {
            MigrationSource source = invocation.getArgument(0);
            source.setId(UUID.randomUUID());
            return source;
        });

        MigrationService service = new MigrationService(sources, batches, execution, migrationSettings(true), crypto(), mock(LegacyMigrationItemRepository.class));

        var response = service.createSource(new MigrationSourceRequest(
                "Office B", MigrationSourceType.LEGACY_DB, null, "ARC2", null, null, null, null, null));

        assertFalse(response.hasStoredConnection());
        verify(sources).save(any());
    }

    @Test
    void createSourceRejectsADuplicateOffice() {
        MigrationSourceRepository sources = mock(MigrationSourceRepository.class);
        MigrationBatchRepository batches = mock(MigrationBatchRepository.class);
        MigrationExecutionService execution = mock(MigrationExecutionService.class);

        when(sources.existsByOfficeName("ARC1")).thenReturn(true);

        MigrationService service = new MigrationService(sources, batches, execution, migrationSettings(true), crypto(), mock(LegacyMigrationItemRepository.class));

        assertThrows(IllegalArgumentException.class, () -> service.createSource(new MigrationSourceRequest(
                "Another name for ARC1", MigrationSourceType.POSTGRESQL, "arc1_db", "ARC1",
                null, null, null, null, null)));
        verify(sources, never()).save(any());
    }

    @Test
    void updateSourceRefusesToRenameTheOfficeOnceRowsHaveBeenMigrated() {
        MigrationSourceRepository sources = mock(MigrationSourceRepository.class);
        MigrationBatchRepository batches = mock(MigrationBatchRepository.class);
        MigrationExecutionService execution = mock(MigrationExecutionService.class);
        LegacyMigrationItemRepository items = mock(LegacyMigrationItemRepository.class);

        UUID sourceId = UUID.randomUUID();
        MigrationSource source = new MigrationSource();
        source.setId(sourceId);
        source.setName("Office A");
        source.setOfficeName("ARC1");
        when(sources.findById(sourceId)).thenReturn(Optional.of(source));
        when(items.countByMigrationSourceId(sourceId)).thenReturn(29L);

        MigrationService service = new MigrationService(
                sources, batches, execution, migrationSettings(true), crypto(), items);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                service.updateSource(sourceId,
                        new MigrationSourceUpdateRequest("Office A renamed", null, null, "ARC9", null, null, null, null, null)));
        assertTrue(error.getMessage().contains("office cannot be changed"));
        assertEquals("ARC1", source.getOfficeName());
        verify(sources, never()).save(any());
    }

    @Test
    void updateSourceAllowsCorrectingTheOfficeOfASourceThatHasMigratedNothing() {
        MigrationSourceRepository sources = mock(MigrationSourceRepository.class);
        MigrationBatchRepository batches = mock(MigrationBatchRepository.class);
        MigrationExecutionService execution = mock(MigrationExecutionService.class);
        LegacyMigrationItemRepository items = mock(LegacyMigrationItemRepository.class);

        UUID sourceId = UUID.randomUUID();
        MigrationSource source = new MigrationSource();
        source.setId(sourceId);
        source.setName("ARC1");
        // A placeholder office name left over from a test insert.
        source.setOfficeName("123");
        when(sources.findById(sourceId)).thenReturn(Optional.of(source));
        when(items.countByMigrationSourceId(sourceId)).thenReturn(0L);
        when(sources.save(any(MigrationSource.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MigrationService service = new MigrationService(
                sources, batches, execution, migrationSettings(true), crypto(), items);

        service.updateSource(sourceId,
                new MigrationSourceUpdateRequest(null, null, null, "ARC1", null, null, null, null, null));

        ArgumentCaptor<MigrationSource> captor = ArgumentCaptor.forClass(MigrationSource.class);
        verify(sources).save(captor.capture());
        assertEquals("ARC1", captor.getValue().getOfficeName());
    }

    @Test
    void updateSourceKeepsTheStoredPasswordWhenTheFieldIsLeftBlank() {
        MigrationSourceRepository sources = mock(MigrationSourceRepository.class);
        MigrationBatchRepository batches = mock(MigrationBatchRepository.class);
        MigrationExecutionService execution = mock(MigrationExecutionService.class);

        UUID sourceId = UUID.randomUUID();
        MigrationSource source = new MigrationSource();
        source.setId(sourceId);
        source.setOfficeName("ARC1");
        source.setDatabaseName("arc1_db");
        source.setHost("10.0.0.5");
        source.setPort(5432);
        source.setUsername("arc1_reader");
        source.setPasswordEncrypted("already-encrypted-value");

        when(sources.findById(sourceId)).thenReturn(Optional.of(source));
        when(sources.save(any(MigrationSource.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MigrationService service = new MigrationService(sources, batches, execution, migrationSettings(true), crypto(), mock(LegacyMigrationItemRepository.class));

        service.updateSource(sourceId,
                new MigrationSourceUpdateRequest(null, null, null, null, "10.0.0.9", null, null, "  ", null));

        ArgumentCaptor<MigrationSource> captor = ArgumentCaptor.forClass(MigrationSource.class);
        verify(sources).save(captor.capture());
        assertEquals("10.0.0.9", captor.getValue().getHost(), "the host should be updated");
        assertEquals("already-encrypted-value", captor.getValue().getPasswordEncrypted(),
                "a blank password must not overwrite the stored one");
    }
}
