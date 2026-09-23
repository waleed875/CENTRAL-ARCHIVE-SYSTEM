package com.centralArchiveSystem.Archive.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "archive.storage.reconciliation.enabled",
        havingValue = "true"
)
public class StorageReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(StorageReconciliationJob.class);

    private final StorageReconciliationService reconciliationService;

    public StorageReconciliationJob(StorageReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Scheduled(cron = "${archive.storage.reconciliation.cron:0 0 3 * * *}")
    public void reconcile() {
        try {
            StorageReconciliationReport report = reconciliationService.reconcile();
            if (report.healthy()) {
                log.info(
                        "Storage reconciliation healthy: bucket={}, files={}, packages={}, exports={}, objects={}",
                        report.bucket(),
                        report.databaseFileRecordsChecked(),
                        report.originalPackagesChecked(),
                        report.exportedPackagesChecked(),
                        report.objectKeysChecked()
                );
                return;
            }

            log.warn(
                    "Storage reconciliation found inconsistencies: bucket={}, dbWithoutObject={}, orphanObjects={}, missingOriginalPackages={}, missingExports={}",
                    report.bucket(),
                    report.databaseRecordsWithoutObject(),
                    report.orphanObjects(),
                    report.missingOriginalPackages(),
                    report.missingExportedPackages()
            );
        } catch (Exception e) {
            log.error("Storage reconciliation failed", e);
        }
    }
}
