package com.centralArchiveSystem.Archive.import_.migration.legacy;

import com.centralArchiveSystem.Archive.import_.migration.MigrationBatch;
import com.centralArchiveSystem.Archive.import_.migration.MigrationBatchRepository;
import com.centralArchiveSystem.Archive.import_.migration.MigrationSourceRepository;
import com.centralArchiveSystem.Archive.import_.migration.setting.MigrationSettingsService;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MigrationRunnerCancellationTest {

    @Test
    void cancellationRegistrySignalsOnlyPreparedBatches() {
        MigrationRunner runner = new MigrationRunner(
                mock(LegacyMigrationService.class),
                mock(MigrationBatchRepository.class),
                mock(MigrationSourceRepository.class),
                mock(LegacyMigrationItemRepository.class),
                mock(MigrationSettingsService.class));
        UUID preparedBatch = UUID.randomUUID();
        UUID unknownBatch = UUID.randomUUID();

        runner.prepareCancellation(preparedBatch);

        assertTrue(runner.requestCancellation(preparedBatch));
        assertTrue(runner.requestCancellation(preparedBatch));
        assertEquals(false, runner.requestCancellation(unknownBatch));
    }

    @Test
    void completingOneBatchDoesNotEraseAnotherPreparedCancellation() {
        LegacyMigrationService legacy = mock(LegacyMigrationService.class);
        MigrationBatchRepository batches = mock(MigrationBatchRepository.class);
        MigrationSourceRepository sources = mock(MigrationSourceRepository.class);
        LegacyMigrationItemRepository items = mock(LegacyMigrationItemRepository.class);
        MigrationSettingsService settings = mock(MigrationSettingsService.class);

        UUID firstId = UUID.randomUUID(), queuedId = UUID.randomUUID();
        MigrationBatch first = new MigrationBatch();
        first.setId(firstId);
        first.setMigrationSourceId(UUID.randomUUID());
        first.setStatus(MigrationBatch.Status.RUNNING);
        when(batches.findById(firstId)).thenReturn(Optional.of(first));
        when(batches.save(any(MigrationBatch.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MigrationRunner runner = new MigrationRunner(legacy, batches, sources, items, settings);
        runner.prepareCancellation(queuedId);
        MigrationCancellationToken cancelled = new MigrationCancellationToken();
        cancelled.requestCancellation();

        assertTrue(runner.runBatch(firstId, cancelled));
        assertEquals(MigrationBatch.Status.CANCELLED, first.getStatus());
        assertTrue(runner.requestCancellation(queuedId),
                "finishing one batch must not discard another queued batch's stop token");
        verify(legacy, never()).openConnection(any());
    }

    @Test
    void rejectedSelectiveTriggerKeepsItsPreparedCancellationToken() {
        MigrationRunner runner = new MigrationRunner(
                mock(LegacyMigrationService.class),
                mock(MigrationBatchRepository.class),
                mock(MigrationSourceRepository.class),
                mock(LegacyMigrationItemRepository.class),
                mock(MigrationSettingsService.class));
        var running = (java.util.concurrent.atomic.AtomicBoolean)
                org.springframework.test.util.ReflectionTestUtils.getField(runner, "running");
        running.set(true); // another batch owns the worker
        UUID queuedId = UUID.randomUUID();

        assertEquals(false, runner.runSelectedFiles(queuedId, java.util.List.of(227)));
        assertTrue(runner.requestCancellation(queuedId),
                "rejected trigger must retain its cancellation token until the queued job starts");
    }

    @Test
    void requestedCancellationFinalizesBatchWithoutOpeningLegacyConnection() {
        LegacyMigrationService legacy = mock(LegacyMigrationService.class);
        MigrationBatchRepository batches = mock(MigrationBatchRepository.class);
        MigrationSourceRepository sources = mock(MigrationSourceRepository.class);
        LegacyMigrationItemRepository items = mock(LegacyMigrationItemRepository.class);
        MigrationSettingsService settings = mock(MigrationSettingsService.class);

        UUID batchId = UUID.randomUUID();
        MigrationBatch batch = new MigrationBatch();
        batch.setId(batchId);
        batch.setMigrationSourceId(UUID.randomUUID());
        batch.setBatchNumber("B-CANCEL");
        batch.setStatus(MigrationBatch.Status.RUNNING);

        when(batches.findById(batchId)).thenReturn(Optional.of(batch));
        when(batches.save(any(MigrationBatch.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MigrationRunner runner = new MigrationRunner(legacy, batches, sources, items, settings);
        MigrationCancellationToken cancellation = new MigrationCancellationToken();
        cancellation.requestCancellation();

        assertTrue(runner.runBatch(batchId, cancellation));

        assertEquals(MigrationBatch.Status.CANCELLED, batch.getStatus());
        assertEquals(false, batch.isCancelRequested());
        assertTrue(batch.getCompletedAt() != null);
        verify(legacy, never()).openConnection(any());
        verify(batches).save(batch);
    }
}
