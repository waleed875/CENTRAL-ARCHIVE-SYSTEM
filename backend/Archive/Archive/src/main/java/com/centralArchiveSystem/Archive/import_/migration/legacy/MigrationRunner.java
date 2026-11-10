package com.centralArchiveSystem.Archive.import_.migration.legacy;

import com.centralArchiveSystem.Archive.import_.migration.MigrationBatchRepository;
import com.centralArchiveSystem.Archive.import_.migration.MigrationBatch;
import com.centralArchiveSystem.Archive.import_.migration.MigrationSource;
import com.centralArchiveSystem.Archive.import_.migration.MigrationSourceRepository;
import com.centralArchiveSystem.Archive.import_.migration.MigrationSourceType;
import com.centralArchiveSystem.Archive.import_.migration.setting.MigrationSettingsService;
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
    private final LegacyMigrationItemRepository itemRepository;
    private final MigrationSettingsService migrationSettingsService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${legacy.migration.schedule-enabled:false}")
    private boolean scheduleEnabled;

    @Value("${legacy.migration.source-name:ARC2}")
    private String sourceName;

    /**
     * Read only so the auto-created source can be stamped with the database this run will actually
     * use. A source created with a different name looks correct in the sources screen and sends the
     * operator to a database the migration never read.
     */
    @Value("${legacy.datasource.url:}")
    private String legacyJdbcUrl;

    public MigrationRunner(LegacyMigrationService legacyMigrationService,
                           MigrationBatchRepository batchRepository,
                           MigrationSourceRepository sourceRepository,
                           LegacyMigrationItemRepository itemRepository,
                           MigrationSettingsService migrationSettingsService) {
        this.legacyMigrationService = legacyMigrationService;
        this.batchRepository = batchRepository;
        this.sourceRepository = sourceRepository;
        this.itemRepository = itemRepository;
        this.migrationSettingsService = migrationSettingsService;
    }

    /**
     * Optional automatic trigger. It remains disabled unless both migration
     * execution and scheduling are explicitly enabled.
     */
    @Scheduled(fixedDelayString = "60000")
    public void scheduledTick() {
        if (!scheduleEnabled || !migrationSettingsService.isMigrationEnabled()) {
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

    /**
     * Opens the configured source and executes a harmless read-only probe. This lets operators
     * distinguish bad credentials/network configuration from a disabled migration window before
     * creating or running a batch.
     */
    public String testConnection(UUID sourceId) {
        MigrationSource source = sourceRepository.findById(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Migration source not found: " + sourceId));
        if (!source.getIsActive()) {
            throw new IllegalStateException("Migration source is inactive: " + sourceId);
        }
        try (LegacyMigrationService.LegacyConnection connection = legacyMigrationService.openConnection(source)) {
            Integer result = connection.jdbc().queryForObject("SELECT 1", Integer.class);
            if (result == null || result != 1) {
                throw new IllegalStateException("The source database did not return the expected connection probe");
            }
            return "Connection successful for office " + connection.office();
        } catch (Exception e) {
            throw new IllegalStateException("تعذر الاتصال بمصدر الترحيل «" + source.getName()
                    + "»: " + e.getMessage(), e);
        }
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

            // Each batch reads exactly one organisation's database, so the connection is opened
            // per batch from the source record and closed when the batch finishes.
            MigrationSource source = sourceRepository.findById(batch.getMigrationSourceId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Migration source not found: " + batch.getMigrationSourceId()));
            if (!source.getIsActive()) {
                throw new IllegalStateException("Migration source is inactive: " + source.getId());
            }

            try (LegacyMigrationService.LegacyConnection connection =
                         legacyMigrationService.openConnection(source)) {
                int refs = legacyMigrationService.migrateReferences(connection);
                int projects = legacyMigrationService.migrateProjects(connection);
                LegacyMigrationService.LegacyFileSummary files =
                        legacyMigrationService.migrateFilesAsync(connection, 4).get();

                long doneFiles = files.done();
                long failedFiles = files.failed();

                // A project that violates a unique constraint is tracked as FAILED rather than
                // skipped, so the batch must not be reported as SUCCESS while rows are missing.
                long failedItems = itemRepository.countByMigrationSourceIdAndStatus(
                        source.getId(), LegacyMigrationItem.Status.FAILED);
                long skippedItems = itemRepository.countByMigrationSourceIdAndStatus(
                        source.getId(), LegacyMigrationItem.Status.SKIPPED);

                batch.setProjectsCount(projects);
                batch.setFilesCount((int) doneFiles);
                batch.setStatus(failedFiles > 0 || failedItems > 0
                        ? MigrationBatch.Status.PARTIAL
                        : MigrationBatch.Status.SUCCESS);
                batch.setNotes("office=" + connection.office()
                        + ", references=" + refs + ", projects=" + projects
                        + ", files done=" + doneFiles + ", failed=" + failedFiles
                        + ", skipped=" + files.skipped()
                        + ", failed rows=" + failedItems + ", skipped rows=" + skippedItems);
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
        if (!migrationSettingsService.isMigrationEnabled()) {
            throw new IllegalStateException(
                    "Legacy migration is disabled. Enable it from the migration settings screen, "
                            + "or set LEGACY_MIGRATION_ENABLED=true, only during a controlled migration window");
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
     *
     * <p>The name match must be unambiguous. Taking the first of several substring matches is how an
     * operator ends up migrating one database while reading a log that names another: two sources
     * whose names both contain "ARC2" is an ordinary thing to end up with after copying a source
     * row, and the old code picked one of them without a word. Reading the wrong database does not
     * fail -- it reports a clean run of zero files.
     */
    public UUID ensureSourceId() {
        List<MigrationSource> allCandidates = sourceRepository.findByNameContainingIgnoreCase(sourceName);
        List<MigrationSource> candidates = allCandidates.stream()
                .filter(MigrationSource::getIsActive).toList();
        if (candidates.isEmpty() && !allCandidates.isEmpty()) {
            throw new IllegalStateException("All migration sources matching '" + sourceName
                    + "' are inactive. Reactivate or configure an active source before running the scheduled migration.");
        }

        List<MigrationSource> exact = candidates.stream()
                .filter(s -> sourceName.equalsIgnoreCase(s.getName()))
                .toList();
        if (exact.size() == 1) {
            return exact.get(0).getId();
        }
        if (exact.size() > 1) {
            throw new IllegalStateException(
                    "There are " + exact.size() + " migration sources named exactly '" + sourceName
                            + "' (" + names(exact) + "). The legacy migration will not pick one. Remove or "
                            + "rename the duplicates.");
        }

        if (candidates.size() == 1) {
            log.warn("Using migration source '{}' because it is the only source whose name contains '{}'. "
                            + "Set legacy.migration.source-name to its exact name to remove the guess.",
                    candidates.get(0).getName(), sourceName);
            return candidates.get(0).getId();
        }
        if (candidates.size() > 1) {
            throw new IllegalStateException(
                    "Migration source '" + sourceName + "' is ambiguous: " + candidates.size()
                            + " sources contain that name (" + names(candidates) + "). Set "
                            + "legacy.migration.source-name to the exact name of the source that points at "
                            + "the legacy database. Refusing to choose, because the wrong one reads an empty "
                            + "database and reports a successful migration of zero files.");
        }

        String database = configuredLegacyDatabase();
        MigrationSource source = new MigrationSource();
        source.setName(sourceName);
        source.setSourceType(MigrationSourceType.LEGACY_DB);
        // Taken from legacy.datasource.url rather than a name written here. This record deliberately
        // carries no host, so hasStoredConnection() is false and openConnection() reads that URL; a
        // database name that disagreed with it would be a lie the sources screen repeats back.
        source.setDatabaseName(database);
        source.setOfficeName(sourceName);
        source.setDescription("Legacy archive system source migrated into central archive"
                + (database == null
                        ? "; no database name could be read from legacy.datasource.url"
                        : "; reads database '" + database + "'"));
        MigrationSource saved = sourceRepository.save(source);
        log.info("Created default migration source: {} id={} database={}", saved.getName(), saved.getId(),
                database == null ? "<none>" : database);
        if (database == null) {
            log.warn("legacy.datasource.url is unset or carries no database name, so the source was "
                    + "created without one. It will still read legacy.datasource.url when it runs.");
        }
        return saved.getId();
    }

    private static String names(List<MigrationSource> sources) {
        return sources.stream().map(MigrationSource::getName).sorted().toList().toString();
    }

    /**
     * The database the connection will actually use, extracted from a JDBC URL.
     *
     * @return the database name, or {@code null} when the URL is absent or unparseable
     */
    String configuredLegacyDatabase() {
        if (legacyJdbcUrl == null || legacyJdbcUrl.isBlank()) {
            return null;
        }
        int authority = legacyJdbcUrl.indexOf("//");
        if (authority < 0) {
            return null;
        }
        String path = legacyJdbcUrl.substring(authority + 2);
        int slash = path.indexOf('/');
        if (slash < 0) {
            return null;
        }
        path = path.substring(slash + 1);
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        path = path.trim();
        return path.isBlank() ? null : path;
    }
}
