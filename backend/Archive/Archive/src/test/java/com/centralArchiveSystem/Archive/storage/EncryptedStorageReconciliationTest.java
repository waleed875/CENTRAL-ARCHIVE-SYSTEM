package com.centralArchiveSystem.Archive.storage;

import com.centralArchiveSystem.Archive.config.MinioConfig;
import com.centralArchiveSystem.Archive.import_.package_.*;
import com.centralArchiveSystem.Archive.import_.package_.export.export.ProjectExportRepository;
import com.centralArchiveSystem.Archive.project.file.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class EncryptedStorageReconciliationTest {
    @Test void encryptedObjectsAreExpectedRatherThanOrphans() throws Exception { check(true, true, 0); }
    @Test void oneMissingEncryptedObjectMakesThePackageIncomplete() throws Exception { check(false, true, 1); }
    @Test void twoMissingObjectsCountAsOneMissingPackage() throws Exception { check(false, false, 1); }

    private void check(boolean decrypted, boolean canonical, int missing) throws Exception {
        FileRepository files=mock(FileRepository.class);
        BranchPackageRepository packages=mock(BranchPackageRepository.class);
        ProjectExportRepository exports=mock(ProjectExportRepository.class);
        MinioService minio=mock(MinioService.class);
        MinioConfig config=new MinioConfig(); config.setBucket("test");
        BranchPackage pkg=new BranchPackage(); pkg.setId(UUID.randomUUID());
        pkg.setPackageUuid(UUID.randomUUID()); pkg.setEncrypted(true);
        String prefix="packages/"+pkg.getPackageUuid()+"/";
        when(files.findAll()).thenReturn(List.of()); when(packages.findAll()).thenReturn(List.of(pkg));
        when(exports.findAll()).thenReturn(List.of()); when(minio.bucketExists("test")).thenReturn(true);
        when(minio.objectExists("test",prefix+"decrypted-payload.zip")).thenReturn(decrypted);
        when(minio.objectExists("test",prefix+"import-ready.pkg")).thenReturn(canonical);
        when(minio.listObjectKeys(anyString(),anyString())).thenReturn(List.of());
        when(minio.listObjectKeys("test","packages/")).thenReturn(
                List.of(prefix+"decrypted-payload.zip",prefix+"import-ready.pkg"));
        var report=new StorageReconciliationService(files,packages,exports,minio,config).reconcile();
        assertEquals(missing,report.missingOriginalPackages()); assertEquals(0,report.orphanObjects());
        verify(minio,never()).objectExists("test",prefix+"original.pkg");
    }
}
