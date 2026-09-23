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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class StorageReconciliationService {

    private static final int SAMPLE_LIMIT = 100;
    private static final List<String> MANAGED_PREFIXES = List.of(
            "projects/",
            "import/",
            "legacy/migration/",
            "packages/",
            "exports/"
    );

    private final FileRepository fileRepository;
    private final BranchPackageRepository branchPackageRepository;
    private final ProjectExportRepository projectExportRepository;
    private final MinioService minioService;
    private final MinioConfig minioConfig;

    public StorageReconciliationService(FileRepository fileRepository,
                                        BranchPackageRepository branchPackageRepository,
                                        ProjectExportRepository projectExportRepository,
                                        MinioService minioService,
                                        MinioConfig minioConfig) {
        this.fileRepository = fileRepository;
        this.branchPackageRepository = branchPackageRepository;
        this.projectExportRepository = projectExportRepository;
        this.minioService = minioService;
        this.minioConfig = minioConfig;
    }

    public StorageReconciliationReport reconcile() throws Exception {
        String configuredBucket = minioConfig.getBucket();
        if (!minioService.bucketExists(configuredBucket)) {
            throw new IllegalStateException(
                    "Required MinIO bucket is missing: " + configuredBucket);
        }

        List<FileEntity> files = fileRepository.findAll();
        List<BranchPackage> packages = branchPackageRepository.findAll();
        List<ProjectExport> exports = projectExportRepository.findAll();

        Set<ObjectRef> expectedFileObjects = new LinkedHashSet<>();
        List<String> missingDbObjectSamples = new ArrayList<>();
        int missingDbObjects = 0;

        for (FileEntity file : files) {
            if (file.getBucketName() == null || file.getObjectKey() == null) {
                missingDbObjects++;
                addSample(missingDbObjectSamples, "file:" + file.getId() + ":missing-storage-reference");
                continue;
            }

            ObjectRef ref = new ObjectRef(file.getBucketName(), file.getObjectKey());
            expectedFileObjects.add(ref);

            if (!minioService.objectExists(ref.bucket(), ref.key())) {
                missingDbObjects++;
                addSample(missingDbObjectSamples, "file:" + file.getId() + ":" + ref.key());
            }
        }

        Set<String> expectedConfiguredBucketKeys = new HashSet<>();
        for (ObjectRef ref : expectedFileObjects) {
            if (configuredBucket.equals(ref.bucket())) {
                expectedConfiguredBucketKeys.add(ref.key());
            }
        }

        List<String> missingOriginalSamples = new ArrayList<>();
        int missingOriginal = 0;
        for (BranchPackage pkg : packages) {
            String key = "packages/" + pkg.getPackageUuid() + "/original.pkg";
            expectedConfiguredBucketKeys.add(key);
            if (!minioService.objectExists(configuredBucket, key)) {
                missingOriginal++;
                addSample(missingOriginalSamples, pkg.getId() + ":" + key);
            }
        }

        List<ProjectExport> completedExports = exports.stream()
                .filter(export -> "EXPORTED".equalsIgnoreCase(export.getStatus()))
                .filter(export -> export.getPackageObjectKey() != null
                        && !export.getPackageObjectKey().isBlank())
                .toList();

        List<String> missingExportSamples = new ArrayList<>();
        int missingExports = 0;
        for (ProjectExport export : completedExports) {
            String key = export.getPackageObjectKey();
            expectedConfiguredBucketKeys.add(key);
            if (!minioService.objectExists(configuredBucket, key)) {
                missingExports++;
                addSample(missingExportSamples, export.getId() + ":" + key);
            }
        }

        Set<String> actualManagedKeys = new LinkedHashSet<>();
        for (String prefix : MANAGED_PREFIXES) {
            actualManagedKeys.addAll(minioService.listObjectKeys(configuredBucket, prefix));
        }

        List<String> orphanSamples = new ArrayList<>();
        int orphanObjects = 0;
        for (String key : actualManagedKeys) {
            if (!expectedConfiguredBucketKeys.contains(key)) {
                orphanObjects++;
                addSample(orphanSamples, key);
            }
        }

        return new StorageReconciliationReport(
                LocalDateTime.now(),
                configuredBucket,
                files.size(),
                packages.size(),
                completedExports.size(),
                actualManagedKeys.size(),
                missingDbObjects,
                orphanObjects,
                missingOriginal,
                missingExports,
                List.copyOf(missingDbObjectSamples),
                List.copyOf(orphanSamples),
                List.copyOf(missingOriginalSamples),
                List.copyOf(missingExportSamples)
        );
    }

    private void addSample(List<String> samples, String value) {
        if (samples.size() < SAMPLE_LIMIT) {
            samples.add(value);
        }
    }

    private record ObjectRef(String bucket, String key) {}
}
