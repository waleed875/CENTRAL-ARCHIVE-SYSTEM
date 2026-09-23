package com.centralArchiveSystem.Archive.storage;

import java.time.LocalDateTime;
import java.util.List;

public record StorageReconciliationReport(
        LocalDateTime checkedAt,
        String bucket,
        int databaseFileRecordsChecked,
        int originalPackagesChecked,
        int exportedPackagesChecked,
        int objectKeysChecked,
        int databaseRecordsWithoutObject,
        int orphanObjects,
        int missingOriginalPackages,
        int missingExportedPackages,
        List<String> databaseRecordsWithoutObjectSamples,
        List<String> orphanObjectSamples,
        List<String> missingOriginalPackageSamples,
        List<String> missingExportedPackageSamples
) {
    public boolean healthy() {
        return databaseRecordsWithoutObject == 0
                && orphanObjects == 0
                && missingOriginalPackages == 0
                && missingExportedPackages == 0;
    }
}
