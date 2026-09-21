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

        when(batches.findById(id)).thenReturn(Optional.of(batch));
        when(batches.save(batch)).thenReturn(batch);

        MigrationService service = new MigrationService(sources, batches, execution);

        var response = service.runBatch(
                id,
                new MigrationRunRequest(
                        "jdbc:postgresql://legacy/db",
                        null,
                        "LEGACY_DB",
                        Map.of()
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

        when(batches.findById(id)).thenReturn(Optional.of(batch));

        MigrationService service = new MigrationService(sources, batches, execution);

        assertThrows(
                IllegalStateException.class,
                () -> service.runBatch(
                        id,
                        new MigrationRunRequest(
                                "jdbc:postgresql://legacy/db",
                                null,
                                "LEGACY_DB",
                                Map.of()
                        )
                )
        );

        verify(execution, never()).execute(any());
        verify(batches, never()).save(any());
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

        MigrationService service = new MigrationService(sources, batches, execution);

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
