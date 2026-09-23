package com.centralArchiveSystem.Archive.storage;

import com.centralArchiveSystem.Archive.config.MinioConfig;
import com.centralArchiveSystem.Archive.import_.package_.BranchPackage;
import com.centralArchiveSystem.Archive.import_.package_.BranchPackageRepository;
import com.centralArchiveSystem.Archive.import_.package_.export.export.ProjectExport;
import com.centralArchiveSystem.Archive.import_.package_.export.export.ProjectExportRepository;
import com.centralArchiveSystem.Archive.project.file.FileEntity;
import com.centralArchiveSystem.Archive.project.file.FileRepository;
import com.centralArchiveSystem.Archive.project.file.MinioService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorageReconciliationServiceTest {

    @Test
    void detectsAllPlannedPostgresMinioDriftClasses() throws Exception {
        FileRepository files = mock(FileRepository.class);
        BranchPackageRepository packages = mock(BranchPackageRepository.class);
        ProjectExportRepository exports = mock(ProjectExportRepository.class);
        MinioService minio = mock(MinioService.class);

        MinioConfig config = new MinioConfig();
        config.setBucket("archive-files");

        FileEntity file = new FileEntity();
        file.setId(UUID.randomUUID());
        file.setBucketName("archive-files");
        file.setObjectKey("projects/p1/missing.txt");

        BranchPackage pkg = new BranchPackage();
        pkg.setId(UUID.randomUUID());
        pkg.setPackageUuid(UUID.randomUUID());

        ProjectExport export = new ProjectExport();
        export.setId(UUID.randomUUID());
        export.setPackageUuid(UUID.randomUUID());
        export.setStatus("EXPORTED");
        export.setPackageObjectKey("exports/e1/export.pkg");

        when(files.findAll()).thenReturn(List.of(file));
        when(packages.findAll()).thenReturn(List.of(pkg));
        when(exports.findAll()).thenReturn(List.of(export));
        when(minio.bucketExists("archive-files")).thenReturn(true);

        when(minio.objectExists("archive-files", file.getObjectKey())).thenReturn(false);
        when(minio.objectExists(
                "archive-files",
                "packages/" + pkg.getPackageUuid() + "/original.pkg"
        )).thenReturn(false);
        when(minio.objectExists("archive-files", export.getPackageObjectKey())).thenReturn(false);

        when(minio.listObjectKeys("archive-files", "projects/"))
                .thenReturn(List.of("projects/orphan/untracked.bin"));
        when(minio.listObjectKeys("archive-files", "import/")).thenReturn(List.of());
        when(minio.listObjectKeys("archive-files", "legacy/migration/")).thenReturn(List.of());
        when(minio.listObjectKeys("archive-files", "packages/")).thenReturn(List.of());
        when(minio.listObjectKeys("archive-files", "exports/")).thenReturn(List.of());

        StorageReconciliationReport report = new StorageReconciliationService(
                files, packages, exports, minio, config
        ).reconcile();

        assertFalse(report.healthy());
        assertEquals(1, report.databaseRecordsWithoutObject());
        assertEquals(1, report.orphanObjects());
        assertEquals(1, report.missingOriginalPackages());
        assertEquals(1, report.missingExportedPackages());
        assertTrue(report.databaseRecordsWithoutObjectSamples().get(0)
                .contains(file.getId().toString()));
        assertEquals("projects/orphan/untracked.bin", report.orphanObjectSamples().get(0));
    }

    @Test
    void ignoresUnmanagedMinioPrefixesWhenLookingForOrphans() throws Exception {
        FileRepository files = mock(FileRepository.class);
        BranchPackageRepository packages = mock(BranchPackageRepository.class);
        ProjectExportRepository exports = mock(ProjectExportRepository.class);
        MinioService minio = mock(MinioService.class);

        MinioConfig config = new MinioConfig();
        config.setBucket("archive-files");

        when(files.findAll()).thenReturn(List.of());
        when(packages.findAll()).thenReturn(List.of());
        when(exports.findAll()).thenReturn(List.of());
        when(minio.bucketExists("archive-files")).thenReturn(true);
        when(minio.listObjectKeys(anyString(), anyString())).thenReturn(List.of());

        StorageReconciliationReport report = new StorageReconciliationService(
                files, packages, exports, minio, config
        ).reconcile();

        assertTrue(report.healthy());
        assertEquals(0, report.orphanObjects());
        verify(minio, never()).listObjectKeys("archive-files", "ci/");
    }
}
