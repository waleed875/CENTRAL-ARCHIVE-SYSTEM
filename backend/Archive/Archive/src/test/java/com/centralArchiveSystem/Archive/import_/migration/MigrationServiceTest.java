package com.centralArchiveSystem.Archive.import_.migration;

import com.centralArchiveSystem.Archive.import_.migration.dto.MigrationBatchCompleteRequest;
import com.centralArchiveSystem.Archive.import_.migration.dto.MigrationRunRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MigrationServiceTest {

    private static final String LEGACY_URL = "jdbc:postgresql://legacy/db";

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
        source.setSourceType("POSTGRESQL");

        when(batches.findById(id)).thenReturn(Optional.of(batch));
        when(batches.save(batch)).thenReturn(batch);
        when(sources.findById(batch.getMigrationSourceId())).thenReturn(Optional.of(source));

        MigrationService service = new MigrationService(sources, batches, execution, LEGACY_URL, true);

        var response = service.runBatch(
                id,
                new MigrationRunRequest(
                        new MigrationRunRequest.MigrationConfig(
                                LEGACY_URL,
                                null,
                                Map.of(),
                                null
                        )
                )
        );

        assertEquals(MigrationBatch.Status.RUNNING.name(), response.status());
        assertNotNull(batch.getStartedAt());
        assertNull(batch.getCompletedAt());
        verify(batches).save(batch);
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
        source.setSourceType("POSTGRESQL");

        when(batches.findById(id)).thenReturn(Optional.of(batch));
        when(sources.findById(batch.getMigrationSourceId())).thenReturn(Optional.of(source));

        MigrationService service = new MigrationService(sources, batches, execution, LEGACY_URL, true);

        assertThrows(
                IllegalStateException.class,
                () -> service.runBatch(
                        id,
                        new MigrationRunRequest(
                                new MigrationRunRequest.MigrationConfig(
                                        LEGACY_URL,
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
                sources, batches, execution, LEGACY_URL, false);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.runBatch(
                        id,
                        new MigrationRunRequest(
                                new MigrationRunRequest.MigrationConfig(
                                        LEGACY_URL,
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
        source.setSourceType("POSTGRESQL");

        when(batches.findById(id)).thenReturn(Optional.of(batch));
        when(sources.findById(sourceId)).thenReturn(Optional.of(source));

        MigrationService service = new MigrationService(
                sources, batches, execution, LEGACY_URL, true);

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
        source.setSourceType("POSTGRESQL");

        when(batches.findById(id)).thenReturn(Optional.of(batch));
        when(sources.findById(sourceId)).thenReturn(Optional.of(source));

        MigrationService service = new MigrationService(
                sources, batches, execution, LEGACY_URL, true);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.runBatch(
                        id,
                        new MigrationRunRequest(
                                new MigrationRunRequest.MigrationConfig(
                                        LEGACY_URL,
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
                                        LEGACY_URL,
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
        source.setSourceType("POSTGRESQL");

        when(batches.findById(id)).thenReturn(Optional.of(batch));
        when(sources.findById(sourceId)).thenReturn(Optional.of(source));

        MigrationService service = new MigrationService(
                sources, batches, execution, LEGACY_URL, true);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.runBatch(
                        id,
                        new MigrationRunRequest(
                                new MigrationRunRequest.MigrationConfig(
                                        LEGACY_URL,
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
        when(sources.existsById(sourceId)).thenReturn(true);
        when(batches.countByMigrationSourceIdAndStatus(
                sourceId,
                MigrationBatch.Status.RUNNING
        )).thenReturn(1L);

        MigrationService service = new MigrationService(sources, batches, execution, LEGACY_URL, true);

        assertThrows(IllegalStateException.class, () -> service.deleteSource(sourceId));
        verify(sources, never()).deleteById(any());
    }

    @Test
    void deleteSourcePreservesCompletedBatchHistory() {
        MigrationSourceRepository sources = mock(MigrationSourceRepository.class);
        MigrationBatchRepository batches = mock(MigrationBatchRepository.class);
        MigrationExecutionService execution = mock(MigrationExecutionService.class);

        UUID sourceId = UUID.randomUUID();
        when(sources.existsById(sourceId)).thenReturn(true);
        when(batches.countByMigrationSourceIdAndStatus(
                sourceId,
                MigrationBatch.Status.RUNNING
        )).thenReturn(0L);
        when(batches.countByMigrationSourceId(sourceId)).thenReturn(2L);

        MigrationService service = new MigrationService(sources, batches, execution, LEGACY_URL, true);

        assertThrows(IllegalStateException.class, () -> service.deleteSource(sourceId));
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

        MigrationService service = new MigrationService(sources, batches, execution, LEGACY_URL, true);

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
}
