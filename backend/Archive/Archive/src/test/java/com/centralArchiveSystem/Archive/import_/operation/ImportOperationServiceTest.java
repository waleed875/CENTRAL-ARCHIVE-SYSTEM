package com.centralArchiveSystem.Archive.import_.operation;

import org.junit.jupiter.api.Test;

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
    void createsStartedOperationWhenPackageHasNoActiveImport() {
        ImportOperationRepository repository = mock(ImportOperationRepository.class);
        UUID packageId = UUID.randomUUID();
        UUID importedBy = UUID.randomUUID();

        when(repository.existsByPackageIdAndStatus(
                packageId,
                ImportOperation.Status.STARTED
        )).thenReturn(false);
        when(repository.save(any(ImportOperation.class))).thenAnswer(invocation -> {
            ImportOperation operation = invocation.getArgument(0);
            operation.setId(UUID.randomUUID());
            return operation;
        });

        ImportOperationService service = new ImportOperationService(repository);
        ImportOperation created = service.create(packageId, importedBy);

        assertNotNull(created.getId());
        assertEquals(packageId, created.getPackageId());
        assertEquals(importedBy, created.getImportedBy());
        assertEquals(ImportOperation.Status.STARTED, created.getStatus());
    }
}
