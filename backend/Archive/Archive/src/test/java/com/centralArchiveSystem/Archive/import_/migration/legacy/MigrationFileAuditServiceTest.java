package com.centralArchiveSystem.Archive.import_.migration.legacy;

import com.centralArchiveSystem.Archive.import_.migration.MigrationSourceRepository;
import com.centralArchiveSystem.Archive.project.file.MinioService;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MigrationFileAuditServiceTest {
    @Test
    void summarizesInvalidDoneLinksAndSamplesMinioWithoutMutatingAnything() throws Exception {
        MigrationSourceRepository sources = mock(MigrationSourceRepository.class);
        LegacyMigrationItemRepository items = mock(LegacyMigrationItemRepository.class);
        MinioService storage = mock(MinioService.class);
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        when(sources.existsById(source)).thenReturn(true);
        when(items.auditFileLinkCounts(source)).thenReturn(
                Arrays.asList(new Object[]{"DONE", 8L, 2L},
                        new Object[]{"FAILED", 3L, 0L}));
        when(items.sampleFileLinkProblems(source)).thenReturn(
                java.util.Collections.singletonList(
                        new Object[]{"FILE-42", "DONE", target, "File metadata row is missing"}));
        when(items.sampleLinkedStorageObjects(source)).thenReturn(
                java.util.Collections.singletonList(
                        new Object[]{"FILE-5", target, "archive", "legacy/test.pdf"}));
        when(storage.objectExists("archive", "legacy/test.pdf")).thenReturn(false);

        var report = new MigrationFileAuditService(sources, items, storage).audit(source);
        assertEquals(8, report.trackingStatuses().get("DONE"));
        assertEquals(3, report.trackingStatuses().get("FAILED"));
        assertEquals(2, report.missingOrInvalidDoneLinks());
        assertEquals("FILE-42", report.problems().getFirst().sourceKey());
        assertEquals("MISSING", report.storageSamples().getFirst().objectStatus());
        verify(storage).objectExists("archive", "legacy/test.pdf");
        verifyNoMoreInteractions(storage);
    }

    @Test
    void marksMinioConnectionErrorsUnknownInsteadOfClaimingObjectsMissing() throws Exception {
        MigrationSourceRepository sources = mock(MigrationSourceRepository.class);
        LegacyMigrationItemRepository items = mock(LegacyMigrationItemRepository.class);
        MinioService storage = mock(MinioService.class);
        UUID source = UUID.randomUUID();
        when(sources.existsById(source)).thenReturn(true);
        when(items.auditFileLinkCounts(source)).thenReturn(List.of());
        when(items.sampleFileLinkProblems(source)).thenReturn(List.of());
        when(items.sampleLinkedStorageObjects(source)).thenReturn(
                java.util.Collections.singletonList(
                        new Object[]{"FILE-1", UUID.randomUUID(), "archive", "old/1"}));
        when(storage.objectExists("archive", "old/1")).thenThrow(new RuntimeException("down"));

        var report = new MigrationFileAuditService(sources, items, storage).audit(source);
        assertEquals("UNKNOWN", report.storageSamples().getFirst().objectStatus());
    }

    @Test
    void rejectsUnknownSourcesBeforeStorageQueries() {
        MigrationSourceRepository sources = mock(MigrationSourceRepository.class);
        LegacyMigrationItemRepository items = mock(LegacyMigrationItemRepository.class);
        MinioService storage = mock(MinioService.class);
        UUID source = UUID.randomUUID();

        assertThrows(RuntimeException.class,
                () -> new MigrationFileAuditService(sources, items, storage).audit(source));
        verifyNoInteractions(items, storage);
    }
}
