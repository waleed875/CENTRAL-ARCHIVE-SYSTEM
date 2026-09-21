package com.centralArchiveSystem.Archive.import_.migration.legacy;

import com.centralArchiveSystem.Archive.import_.migration.MigrationBatchRepository;
import com.centralArchiveSystem.Archive.import_.migration.MigrationBatch;
import com.centralArchiveSystem.Archive.import_.migration.MigrationSource;
import com.centralArchiveSystem.Archive.import_.migration.MigrationSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the legacy migration on a schedule and on demand.
 *
 * Automatic scheduling is gated by legacy.migration.enabled so the scheduler is
 * a no-op until explicitly turned on (prevents unwanted DB access/impact in
 * tests or during normal application operation). Manual triggers are also
 * rejected while migration is disabled.
 */
@Component
public class MigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrationRunner.class);

    private final LegacyMigrationService legacyMigrationService;
    private final MigrationBatchRepository batchRepository;
    private final MigrationSourceRepository sourceRepository;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${legacy.migration.enabled:false}")
    private boolean enabled;

    @Value("${legacy.migration.schedule-enabled:false}")
    private boolean scheduleEnabled;

    @Value("${legacy.migration.source-name:ARC2}")
    private String sourceName;

    public MigrationRunner(LegacyMigrationService legacyMigrationService,
                           MigrationBatchRepository batchRepository,
                           MigrationSourceRepository sourceRepository) {
        this.legacyMigrationService = legacyMigrationService;
        this.batchRepository = batchRepository;
        this.sourceRepository = sourceRepository;
    }

    /**
     * Optional automatic trigger. It remains disabled unless both migration
     * execution and scheduling are explicitly enabled.
     */
    @Scheduled(fixedDelayString = "60000")
    public void scheduledTick() {
        if (!enabled || !scheduleEnabled) {
            return;
        }
        runOnce();
    }

    /**
     * Executes the migration pipeline if not already running. Returns true if a
     * run was (re)started by this call.
     */
    public boolean runOnce() {
        ensureEnabled();
        MigrationBatch batch = findOrCreateRunningBatch();
        return runBatch(batch.getId());
    }

    public boolean runBatch(UUID batchId) {
        ensureEnabled();
        if (!running.compareAndSet(false, true)) {
            log.info("Legacy migration already running, skipped trigger for batch={}", batchId);
            return false;
        }

        try {
            MigrationBatch batch = batchRepository.findById(batchId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Migration batch not found: " + batchId));

            if (batch.getStatus() != MigrationBatch.Status.RUNNING) {
                throw new IllegalStateException(
                        "Migration batch must be RUNNING before execution; current status: "
                                + batch.getStatus());
            }

            log.info("Legacy migration started, batch={}", batch.getId());

            try {
                int refs = legacyMigrationService.migrateReferences();
                int projects = legacyMigrationService.migrateProjects();
                LegacyMigrationService.LegacyFileSummary files =
                        legacyMigrationService.migrateFilesAsync(4).get();

                long doneFiles = files.done();
                long failedFiles = files.failed();

                batch.setProjectsCount(projects);
                batch.setFilesCount((int) doneFiles);
                batch.setStatus(failedFiles > 0
                        ? MigrationBatch.Status.PARTIAL
                        : MigrationBatch.Status.SUCCESS);
                batch.setNotes("references=" + refs + ", projects=" + projects
                        + ", files done=" + doneFiles + ", failed=" + failedFiles
                        + ", skipped=" + files.skipped());
            } catch (Exception e) {
                log.error("Legacy migration failed, batch={}", batchId, e);
                batch.setStatus(MigrationBatch.Status.FAILED);
                batch.setNotes("FAILED: " + e.getMessage());
            } finally {
                batch.setCompletedAt(LocalDateTime.now());
                batchRepository.save(batch);
            }
            return true;
        } finally {
            running.set(false);
        }
    }

    private void ensureEnabled() {
        if (!enabled) {
            throw new IllegalStateException(
                    "Legacy migration is disabled. Enable it only during a controlled migration window");
        }
    }

    private MigrationBatch findOrCreateRunningBatch() {
        java.util.List<MigrationBatch> runningBatches = batchRepository.findByStatus(MigrationBatch.Status.RUNNING);
        if (!runningBatches.isEmpty()) {
            return runningBatches.get(0);
        }
        MigrationBatch batch = new MigrationBatch();
        batch.setMigrationSourceId(ensureSourceId());
        batch.setBatchNumber("AUTO-" + System.currentTimeMillis());
        batch.setStatus(MigrationBatch.Status.RUNNING);
        batch.setStartedAt(LocalDateTime.now());
        batch.setNotes("Legacy migration from staging database");
        return batchRepository.save(batch);
    }

    /**
     * Returns a MigrationSource id for the legacy migration, creating a default
     * one (from legacy.migration.source-name) if none exists. This guarantees a
     * non-null migration_source_id on batches (the column is NOT NULL).
     */
    public UUID ensureSourceId() {
        List<MigrationSource> existing = sourceRepository.findByNameContainingIgnoreCase(sourceName);
        if (!existing.isEmpty()) {
            return existing.get(0).getId();
        }
        MigrationSource source = new MigrationSource();
        source.setName(sourceName);
        source.setSourceType("LEGACY_DB");
        source.setDatabaseName("legacy_staging");
        source.setOfficeName(sourceName);
        source.setDescription("Legacy archive system source migrated into central archive");
        MigrationSource saved = sourceRepository.save(source);
        log.info("Created default migration source: {} id={}", saved.getName(), saved.getId());
        return saved.getId();
    }
}
