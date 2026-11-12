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
