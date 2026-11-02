package com.centralArchiveSystem.Archive.storage;

import com.centralArchiveSystem.Archive.config.MinioConfig;
import com.centralArchiveSystem.Archive.import_.package_.BranchPackage;
import com.centralArchiveSystem.Archive.import_.package_.BranchPackageRepository;
import com.centralArchiveSystem.Archive.import_.package_.export.export.ProjectExport;
import com.centralArchiveSystem.Archive.import_.package_.export.export.ProjectExportRepository;
import com.centralArchiveSystem.Archive.project.file.FileEntity;
import com.centralArchiveSystem.Archive.project.file.FileRepository;
import com.centralArchiveSystem.Archive.project.file.MinioService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

/** Detection only: never delete an object merely because it is absent from a database snapshot. */
@Service
@Transactional(readOnly = true)
public class StorageReconciliationService {
    private static final int SAMPLE_LIMIT = 100;
    private static final List<String> MANAGED_PREFIXES = List.of("projects/", "import/", "legacy/migration/", "packages/", "exports/");
    private final FileRepository fileRepository;
    private final BranchPackageRepository branchPackageRepository;
    private final ProjectExportRepository projectExportRepository;
    private final MinioService minioService;
    private final MinioConfig minioConfig;

    public StorageReconciliationService(FileRepository fileRepository, BranchPackageRepository branchPackageRepository,
            ProjectExportRepository projectExportRepository, MinioService minioService, MinioConfig minioConfig) {
        this.fileRepository = fileRepository; this.branchPackageRepository = branchPackageRepository;
        this.projectExportRepository = projectExportRepository; this.minioService = minioService; this.minioConfig = minioConfig;
    }

    public StorageReconciliationReport reconcile() throws Exception {
        String bucket = minioConfig.getBucket();
        if (!minioService.bucketExists(bucket)) throw new IllegalStateException("Required MinIO bucket is missing: " + bucket);
        List<FileEntity> files = fileRepository.findAll();
        List<BranchPackage> packages = branchPackageRepository.findAll();
        List<ProjectExport> exports = projectExportRepository.findAll();
        Set<String> expected = new HashSet<>();
        List<String> missingDbSamples = new ArrayList<>();
        int missingDb = 0;
        for (FileEntity file : files) {
            if (file.getBucketName() == null || file.getObjectKey() == null) {
                missingDb++; addSample(missingDbSamples, "file:" + file.getId() + ":missing-storage-reference"); continue;
            }
            if (bucket.equals(file.getBucketName())) expected.add(file.getObjectKey());
            if (!minioService.objectExists(file.getBucketName(), file.getObjectKey())) {
                missingDb++; addSample(missingDbSamples, "file:" + file.getId() + ":" + file.getObjectKey());
            }
        }
        List<String> missingPackageSamples = new ArrayList<>();
        int missingPackages = 0;
        for (BranchPackage pkg : packages) {
            String prefix = "packages/" + pkg.getPackageUuid() + "/";
            List<String> keys = Boolean.TRUE.equals(pkg.getEncrypted())
                    ? List.of(prefix + "decrypted-payload.zip", prefix + "import-ready.pkg")
                    : List.of(prefix + "original.pkg");
            boolean missing = false;
            for (String key : keys) {
                expected.add(key);
                if (!minioService.objectExists(bucket, key)) {
                    missing = true; addSample(missingPackageSamples, pkg.getId() + ":" + key);
                }
            }
            if (missing) missingPackages++;
        }
        List<ProjectExport> completed = exports.stream().filter(e -> "EXPORTED".equalsIgnoreCase(e.getStatus()))
                .filter(e -> e.getPackageObjectKey() != null && !e.getPackageObjectKey().isBlank()).toList();
        List<String> missingExportSamples = new ArrayList<>();
        int missingExports = 0;
        for (ProjectExport export : completed) {
            String key = export.getPackageObjectKey(); expected.add(key);
            if (!minioService.objectExists(bucket, key)) {
                missingExports++; addSample(missingExportSamples, export.getId() + ":" + key);
            }
        }
        Set<String> actual = new LinkedHashSet<>();
        for (String prefix : MANAGED_PREFIXES) actual.addAll(minioService.listObjectKeys(bucket, prefix));
        List<String> orphanSamples = new ArrayList<>();
        int orphans = 0;
        for (String key : actual) {
            if (!expected.contains(key)) { orphans++; addSample(orphanSamples, key); }
        }
        return new StorageReconciliationReport(LocalDateTime.now(), bucket, files.size(), packages.size(), completed.size(),
                actual.size(), missingDb, orphans, missingPackages, missingExports,
                List.copyOf(missingDbSamples), List.copyOf(orphanSamples), List.copyOf(missingPackageSamples), List.copyOf(missingExportSamples));
    }

    private void addSample(List<String> samples, String value) { if (samples.size() < SAMPLE_LIMIT) samples.add(value); }
}
