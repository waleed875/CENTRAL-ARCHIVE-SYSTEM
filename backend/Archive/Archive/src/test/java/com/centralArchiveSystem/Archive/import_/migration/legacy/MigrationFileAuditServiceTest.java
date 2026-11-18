package com.centralArchiveSystem.Archive.import_.migration.legacy;

import com.centralArchiveSystem.Archive.import_.migration.MigrationSourceRepository;
import com.centralArchiveSystem.Archive.project.file.MinioService;
import com.centralArchiveSystem.Archive.project.file.FileRepository;
import com.centralArchiveSystem.Archive.config.MinioConfig;
import org.springframework.test.util.ReflectionTestUtils;
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
        when(items.countFailedFileErrorCategories(source)).thenReturn(
                java.util.Arrays.asList(new Object[]{"INVALID_CONTENT", 2L},
                        new Object[]{"TRANSIENT_IO", 1L}));
        when(items.sampleLinkedStorageObjects(source)).thenReturn(
                java.util.Collections.singletonList(
                        new Object[]{"FILE-5", target, "archive", "legacy/test.pdf"}));
        when(storage.objectExists("archive", "legacy/test.pdf")).thenReturn(false);

        var report = new MigrationFileAuditService(sources, items, storage,
                mock(FileRepository.class), mock(MinioConfig.class)).audit(source);
        assertEquals(8, report.trackingStatuses().get("DONE"));
        assertEquals(3, report.trackingStatuses().get("FAILED"));
        assertEquals(2, report.missingOrInvalidDoneLinks());
        assertEquals(2L, report.failedErrorCategories().get("INVALID_CONTENT"));
        assertEquals(1L, report.failedErrorCategories().get("TRANSIENT_IO"));
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

        var report = new MigrationFileAuditService(sources, items, storage,
                mock(FileRepository.class), mock(MinioConfig.class)).audit(source);
        assertEquals("UNKNOWN", report.storageSamples().getFirst().objectStatus());
    }

    @Test
    void reconciliationPagesOnlySourceScopedDoneFilesAndReportsMissingAndUnknown() throws Exception {
        MigrationSourceRepository sources = mock(MigrationSourceRepository.class);
        LegacyMigrationItemRepository items = mock(LegacyMigrationItemRepository.class);
        MinioService minio = mock(MinioService.class);
        UUID sourceId = UUID.randomUUID();
        UUID target1 = UUID.randomUUID();
        UUID target2 = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        when(sources.existsById(sourceId)).thenReturn(true);
        when(items.reconciliationPage(sourceId, null, 3)).thenReturn(java.util.Arrays.asList(
                new Object[]{"FILE-1", target1, target1, project, project, null,
                        "ACTIVE", "archive", "legacy/one"},
                new Object[]{"FILE-2", target2, target2, project, project, null,
                        "QUARANTINED", "archive", "legacy/two"},
                new Object[]{"FILE-3", UUID.randomUUID(), null, null, null, null,
                        null, null, null}));
        when(minio.objectExists("archive", "legacy/one")).thenReturn(false);
        when(minio.objectExists("archive", "legacy/two")).thenThrow(new RuntimeException("storage unavailable"));

        var page = new MigrationFileAuditService(sources, items, minio,
                mock(FileRepository.class), mock(MinioConfig.class))
                .reconcilePage(sourceId, null, 2);

        assertEquals(2, page.checked());
        assertTrue(page.hasMore());
        assertEquals("FILE-2", page.nextCursor());
        assertEquals(1, page.missingObjects());
        assertEquals(1, page.unknown());
        assertEquals(2, page.findings().size());
        assertEquals("MISSING_OBJECT", page.findings().get(0).status());
        assertEquals("UNKNOWN", page.findings().get(1).status());
        verify(items).reconciliationPage(sourceId, null, 3);
        verify(minio).objectExists("archive", "legacy/one");
        verify(minio).objectExists("archive", "legacy/two");
        verifyNoMoreInteractions(minio);
    }

    @Test
    void reconciliationReportsBrokenLinksWithoutReadingMinioAndRejectsBadCursors() {
        MigrationSourceRepository sources = mock(MigrationSourceRepository.class);
        LegacyMigrationItemRepository items = mock(LegacyMigrationItemRepository.class);
        MinioService minio = mock(MinioService.class);
        UUID sourceId = UUID.randomUUID();
        when(sources.existsById(sourceId)).thenReturn(true);
        when(items.reconciliationPage(sourceId, "FILE-9", 2)).thenReturn(
                java.util.Collections.singletonList(
                        new Object[]{"FILE-10", UUID.randomUUID(), null, null, null, null,
                                null, null, null}));

        var service = new MigrationFileAuditService(sources, items, minio,
                mock(FileRepository.class), mock(MinioConfig.class));
        var page = service.reconcilePage(sourceId, "FILE-9", 1);
        assertFalse(page.hasMore());
        assertNull(page.nextCursor());
        assertEquals(1, page.invalidLinks());
        assertEquals("INVALID_LINK", page.findings().getFirst().status());
        verifyNoInteractions(minio);
        assertThrows(IllegalArgumentException.class,
                () -> service.reconcilePage(sourceId, "../other", 10));
        assertThrows(IllegalArgumentException.class,
                () -> service.reconcilePage(sourceId, null, 101));
    }

    private static MigrationFileAuditService inventoryService(
            MigrationSourceRepository sources, LegacyMigrationItemRepository items,
            MinioService storage, FileRepository files, MinioConfig config) {
        var service = new MigrationFileAuditService(sources, items, storage, files, config);
        ReflectionTestUtils.setField(service, "migrationPrefix", "legacy/migration");
        return service;
    }

    @Test
    void orphanInventoryFindsOnlyUnreferencedKeysInMappedProjectPrefix() throws Exception {
        var sources = mock(MigrationSourceRepository.class);
        var items = mock(LegacyMigrationItemRepository.class);
        var storage = mock(MinioService.class);
        var files = mock(FileRepository.class);
        var config = mock(MinioConfig.class);
        UUID sourceId = UUID.randomUUID(), targetProjectId = UUID.randomUUID();
        var project = new LegacyMigrationItem();
        project.setStatus(LegacyMigrationItem.Status.DONE);
        project.setTargetId(targetProjectId);
        when(sources.existsById(sourceId)).thenReturn(true);
        when(items.findByMigrationSourceIdAndKindAndSourceKey(
                sourceId, LegacyMigrationItem.Kind.PROJECT, "PROJECT-42"))
                .thenReturn(java.util.Optional.of(project));
        when(config.getBucket()).thenReturn("archive");
        String prefix = "legacy/migration/files/" + targetProjectId + "/";
        String a = prefix + "a.pdf", b = prefix + "b.pdf", c = prefix + "c.pdf";
        when(storage.listObjectKeysPage("archive", prefix, null, 3))
                .thenReturn(List.of(a, b, c));
        when(files.findReferencedObjectKeys("archive", List.of(a, b)))
                .thenReturn(List.of(a));

        var result = inventoryService(sources, items, storage, files, config)
                .orphanCandidates(sourceId, 42, "files", null, 2);
        assertEquals(2, result.checked());
        assertEquals(1, result.referenced());
        assertEquals(1, result.unreferenced());
        assertEquals(b, result.candidates().getFirst().objectKey());
        assertTrue(result.hasMore());
        assertEquals(b, result.nextCursor());
        verify(files).findReferencedObjectKeys("archive", List.of(a, b));
        verify(storage).listObjectKeysPage("archive", prefix, null, 3);
        verifyNoMoreInteractions(storage);
    }

    @Test
    void orphanInventoryRejectsUnmappedProjectAndCrossPrefixCursor() {
        var sources = mock(MigrationSourceRepository.class);
        var items = mock(LegacyMigrationItemRepository.class);
        var storage = mock(MinioService.class);
        var files = mock(FileRepository.class);
        var config = mock(MinioConfig.class);
        UUID sourceId = UUID.randomUUID(), targetId = UUID.randomUUID();
        when(sources.existsById(sourceId)).thenReturn(true);
        var service = inventoryService(sources, items, storage, files, config);
        assertThrows(IllegalArgumentException.class,
                () -> service.orphanCandidates(sourceId, 42, "files", null, 10));
        var project = new LegacyMigrationItem();
        project.setStatus(LegacyMigrationItem.Status.DONE);
        project.setTargetId(targetId);
        when(items.findByMigrationSourceIdAndKindAndSourceKey(
                sourceId, LegacyMigrationItem.Kind.PROJECT, "PROJECT-42"))
                .thenReturn(java.util.Optional.of(project));
        assertThrows(IllegalArgumentException.class,
                () -> service.orphanCandidates(sourceId, 42, "files", "other/secret", 10));
        verifyNoInteractions(storage, files);
    }

    @Test
    void rejectsUnknownSourcesBeforeStorageQueries() {
        MigrationSourceRepository sources = mock(MigrationSourceRepository.class);
        LegacyMigrationItemRepository items = mock(LegacyMigrationItemRepository.class);
        MinioService storage = mock(MinioService.class);
        UUID source = UUID.randomUUID();

        assertThrows(RuntimeException.class,
                () -> new MigrationFileAuditService(sources, items, storage,
                mock(FileRepository.class), mock(MinioConfig.class)).audit(source));
        verifyNoInteractions(items, storage);
    }
}
