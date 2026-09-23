package com.centralArchiveSystem.Archive.import_.operation;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImportOperationServiceTest {

    @Test
    void rejectsSecondStartedImportForSamePackage() {
        ImportOperationRepository repository = mock(ImportOperationRepository.class);
        UUID packageId = UUID.randomUUID();

        when(repository.existsByPackageIdAndStatus(
                packageId,
                ImportOperation.Status.STARTED
        )).thenReturn(true);

        ImportOperationService service = new ImportOperationService(repository);

        assertThrows(
                IllegalStateException.class,
                () -> service.create(packageId, UUID.randomUUID())
        );

        verify(repository, never()).save(any());
    }

    @Test
    void createsStartedOperationWithSnapshotAndRetryProvenance() {
        ImportOperationRepository repository = mock(ImportOperationRepository.class);
        UUID packageId = UUID.randomUUID();
        UUID importedBy = UUID.randomUUID();
        UUID previousId = UUID.randomUUID();

        ImportOperation previous = new ImportOperation();
        previous.setId(previousId);
        previous.setPackageId(packageId);
        previous.setStatus(ImportOperation.Status.INTERRUPTED);

        when(repository.existsByPackageIdAndStatus(
                packageId,
                ImportOperation.Status.STARTED
        )).thenReturn(false);
        when(repository.findFirstByPackageIdOrderByStartedAtDesc(packageId))
                .thenReturn(Optional.of(previous));
        when(repository.save(any(ImportOperation.class))).thenAnswer(invocation -> {
            ImportOperation operation = invocation.getArgument(0);
            operation.setId(UUID.randomUUID());
            return operation;
        });

        ImportOperationService service = new ImportOperationService(repository);
        ImportOperation created = service.create(
                packageId,
                importedBy,
                "{\"organization_mapping\":{}}"
        );

        assertNotNull(created.getId());
        assertEquals(packageId, created.getPackageId());
        assertEquals(importedBy, created.getImportedBy());
        assertEquals(ImportOperation.Status.STARTED, created.getStatus());
        assertEquals(previousId, created.getRetryOf());
        assertEquals("{\"organization_mapping\":{}}", created.getRequestSnapshot());
        assertNotNull(created.getLastHeartbeatAt());
    }

    @Test
    void startupRecoveryMarksStartedOperationsInterrupted() {
        ImportOperationRepository repository = mock(ImportOperationRepository.class);
        ImportOperation started = new ImportOperation(UUID.randomUUID(), UUID.randomUUID());
        started.setId(UUID.randomUUID());

        when(repository.findByStatus(ImportOperation.Status.STARTED))
                .thenReturn(List.of(started));

        ImportOperationService service = new ImportOperationService(repository);
        service.recoverInterruptedOperationsAfterRestart();

        assertEquals(ImportOperation.Status.INTERRUPTED, started.getStatus());
        assertNotNull(started.getCompletedAt());
        assertNotNull(started.getLastHeartbeatAt());
        assertTrue(started.getErrorMessage().contains("restart"));
        verify(repository).saveAll(List.of(started));
    }

    @Test
    void heartbeatAdvancesOnlyActiveOperation() {
        ImportOperationRepository repository = mock(ImportOperationRepository.class);
        ImportOperation started = new ImportOperation(UUID.randomUUID(), UUID.randomUUID());
        started.setId(UUID.randomUUID());
        started.setLastHeartbeatAt(LocalDateTime.now().minusMinutes(10));

        when(repository.findById(started.getId())).thenReturn(Optional.of(started));
        when(repository.save(started)).thenReturn(started);

        LocalDateTime before = started.getLastHeartbeatAt();

        ImportOperationService service = new ImportOperationService(repository);
        ImportOperation updated = service.heartbeat(started.getId());

        assertTrue(updated.getLastHeartbeatAt().isAfter(before));
        verify(repository).save(started);
    }

    @Test
    void recordsIndependentProjectOutcomeCounters() {
        ImportOperationRepository repository = mock(ImportOperationRepository.class);
        ImportOperation operation = new ImportOperation(UUID.randomUUID(), UUID.randomUUID());
        operation.setId(UUID.randomUUID());

        when(repository.findById(operation.getId())).thenReturn(Optional.of(operation));
        when(repository.save(operation)).thenReturn(operation);

        ImportOperationService service = new ImportOperationService(repository);

        service.initializeProjectStatistics(operation.getId(), 5);
        service.recordProjectOutcome(operation.getId(), ImportOperationService.ProjectOutcome.IMPORTED);
        service.recordProjectOutcome(operation.getId(), ImportOperationService.ProjectOutcome.PARTIAL);
        service.recordProjectOutcome(operation.getId(), ImportOperationService.ProjectOutcome.FAILED);
        service.recordProjectOutcome(operation.getId(), ImportOperationService.ProjectOutcome.DUPLICATE);
        service.recordProjectOutcome(operation.getId(), ImportOperationService.ProjectOutcome.SKIPPED);

        assertEquals(5, operation.getProjectsTotal());
        assertEquals(1, operation.getProjectsImported());
        assertEquals(1, operation.getProjectsPartial());
        assertEquals(1, operation.getProjectsFailed());
        assertEquals(1, operation.getProjectsDuplicate());
        assertEquals(1, operation.getProjectsSkipped());
    }

    @Test
    void terminalStatusRecordsCompletionAndHeartbeat() {
        ImportOperationRepository repository = mock(ImportOperationRepository.class);
        ImportOperation started = new ImportOperation(UUID.randomUUID(), UUID.randomUUID());
        started.setId(UUID.randomUUID());

        when(repository.findById(started.getId())).thenReturn(Optional.of(started));
        when(repository.save(started)).thenReturn(started);

        ImportOperationService service = new ImportOperationService(repository);
        ImportOperation updated = service.updateStatus(
                started.getId(),
                ImportOperation.Status.INTERRUPTED,
                "restart"
        );

        assertEquals(ImportOperation.Status.INTERRUPTED, updated.getStatus());
        assertNotNull(updated.getCompletedAt());
        assertNotNull(updated.getLastHeartbeatAt());
        assertEquals("restart", updated.getErrorMessage());
    }
}
