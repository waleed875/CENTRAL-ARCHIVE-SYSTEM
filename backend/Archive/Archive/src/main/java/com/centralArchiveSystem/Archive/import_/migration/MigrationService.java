package com.centralArchiveSystem.Archive.import_.migration;

import java.util.NoSuchElementException;
import com.centralArchiveSystem.Archive.import_.migration.dto.*;
import com.centralArchiveSystem.Archive.import_.migration.legacy.LegacyMigrationItemRepository;
import com.centralArchiveSystem.Archive.import_.migration.legacy.MigrationRunner;
import com.centralArchiveSystem.Archive.import_.migration.setting.MigrationSettingsService;
import com.centralArchiveSystem.Archive.import_.migration.setting.SettingsCrypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class MigrationService {

    private static final Logger log = LoggerFactory.getLogger(MigrationService.class);

    private final MigrationSourceRepository sourceRepository;
    private final MigrationBatchRepository batchRepository;
    private final MigrationExecutionService migrationExecutionService;
    private final MigrationSettingsService migrationSettingsService;
    private final SettingsCrypto settingsCrypto;
    private final LegacyMigrationItemRepository itemRepository;

    public MigrationService(MigrationSourceRepository sourceRepository,
                            MigrationBatchRepository batchRepository,
                            MigrationExecutionService migrationExecutionService,
                            MigrationSettingsService migrationSettingsService,
                            SettingsCrypto settingsCrypto,
                            LegacyMigrationItemRepository itemRepository) {
        this.sourceRepository = sourceRepository;
        this.batchRepository = batchRepository;
        this.migrationExecutionService = migrationExecutionService;
        this.migrationSettingsService = migrationSettingsService;
        this.settingsCrypto = settingsCrypto;
        this.itemRepository = itemRepository;
    }

    @Transactional(readOnly = true)
    public List<MigrationSourceResponse> listSources() {
        return sourceRepository.findAll().stream()
                .map(this::toSourceResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public MigrationSourceResponse getSourceById(UUID id) {
        MigrationSource source = sourceRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Migration source not found: " + id));
        return toSourceResponse(source);
    }

    public MigrationSourceResponse createSource(MigrationSourceRequest request) {
        MigrationSource source = new MigrationSource();
        source.setName(request.name());
        source.setSourceType(request.sourceType());
        source.setDatabaseName(request.databaseName());
        source.setOfficeName(request.officeName().trim());
        source.setDescription(request.description());
        if (sourceRepository.existsByOfficeName(source.getOfficeName())) {
            throw new IllegalArgumentException(
                    "A migration source for office '" + source.getOfficeName() + "' already exists");
        }
        applyConnection(source, request.host(), request.port(), request.username(), request.password());
        requireCompleteConnection(source);
        MigrationSource saved = sourceRepository.save(source);
        log.info("Created migration source: {} for office {}", saved.getId(), saved.getOfficeName());
        return toSourceResponse(saved);
    }

    public MigrationSourceResponse updateSource(UUID id, MigrationSourceUpdateRequest request) {
        MigrationSource source = sourceRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Migration source not found: " + id));
        if (request.name() != null) source.setName(request.name());
        if (request.sourceType() != null) source.setSourceType(request.sourceType());
        if (request.databaseName() != null) source.setDatabaseName(request.databaseName());
        if (request.description() != null) source.setDescription(request.description());

        if (request.officeName() != null && !request.officeName().isBlank()
                && !request.officeName().trim().equals(source.getOfficeName())) {
            // Renaming the office rewrites the identity of everything already migrated under it.
            // A source that has migrated nothing can still be corrected, otherwise a source created
            // with a placeholder office name could never be fixed.
            long migrated = itemRepository.countByMigrationSourceId(id);
            if (migrated > 0) {
                throw new IllegalArgumentException(
                        "The office cannot be changed once " + migrated + " rows have been migrated from "
                                + "this source. It is part of every migrated project reference, so changing it "
                                + "would orphan them. Create a new source for the other office instead.");
            }
            source.setOfficeName(request.officeName().trim());
        }

        // A blank password keeps the stored one so an editor can fix the host without retyping it.
        boolean connectionTouched = request.host() != null
                || request.port() != null
                || request.username() != null
                || (request.password() != null && !request.password().isBlank());
        if (connectionTouched) {
            applyConnection(
                    source,
                    request.host() != null ? request.host() : source.getHost(),
                    request.port() != null ? request.port() : source.getPort(),
                    request.username() != null ? request.username() : source.getUsername(),
                    request.password());
        }
        // Validate the effective connection even when only databaseName changed.
        requireCompleteConnection(source);

        MigrationSource saved = sourceRepository.save(source);
        log.info("Updated migration source: {}", saved.getId());
        return toSourceResponse(saved);
    }

    /**
     * Stores the per-office connection. The password is encrypted before it reaches the database,
     * and a blank password never overwrites an existing one.
     */
    private void applyConnection(MigrationSource source, String host, Integer port, String username, String password) {
        if (host != null) source.setHost(host.isBlank() ? null : host.trim());
        if (port != null) source.setPort(port);
        if (username != null) source.setUsername(username.isBlank() ? null : username.trim());
        if (password != null && !password.isBlank()) {
            source.setPasswordEncrypted(settingsCrypto.encrypt(password));
        }
    }

    /**
     * A half-filled connection is rejected instead of being stored. {@code hasStoredConnection()} only
     * looks at host, database and password, so a source with a host but no username would be treated
     * as configured and would build an unusable JDBC URL at migration time, far from the form that
     * caused it. Either every field is present, or the source keeps using the environment default.
     */
    private void requireCompleteConnection(MigrationSource source) {
        boolean anyPresent = isPresent(source.getHost())
                || source.getPort() != null
                || isPresent(source.getUsername())
                || isPresent(source.getPasswordEncrypted());
        if (!anyPresent) {
            return;
        }

        List<String> missing = new ArrayList<>();
        if (!isPresent(source.getHost())) missing.add("host");
        if (source.getPort() == null) missing.add("port");
        if (!isPresent(source.getDatabaseName())) missing.add("databaseName");
        if (!isPresent(source.getUsername())) missing.add("username");
        if (!isPresent(source.getPasswordEncrypted())) missing.add("password");

        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "The connection for office '" + source.getOfficeName() + "' is incomplete, missing: "
                            + String.join(", ", missing) + ". Enter all connection fields for this source, "
                            + "or clear the host to keep using the default legacy.datasource.url connection.");
        }
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Remove a source from active use without deleting its batch history or migrated references.
     * The historical row remains addressable so audit and batch records never lose their source.
     */
    public String testSourceConnection(UUID id) {
        MigrationSource source = sourceRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Migration source not found: " + id));
        if (!source.getIsActive()) {
            throw new IllegalStateException("مصدر الترحيل غير نشط");
        }
        return migrationExecutionService.testConnection(id);
    }

    public void deleteSource(UUID id) {
        MigrationSource source = sourceRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Migration source not found: " + id));

        long runningBatches = batchRepository.countByMigrationSourceIdAndStatus(
                id, MigrationBatch.Status.RUNNING);
        if (runningBatches > 0) {
            throw new IllegalStateException("لا يمكن إزالة مصدر أثناء تشغيل دفعة ترحيل");
        }
        if (!source.getIsActive()) {
            throw new IllegalStateException("مصدر الترحيل غير نشط بالفعل");
        }

        source.setIsActive(false);
        sourceRepository.save(source);
        log.info("Deactivated migration source {} while preserving {} batch records",
                id, batchRepository.countByMigrationSourceId(id));
    }

    @Transactional(readOnly = true)
    public List<MigrationBatchResponse> listBatches(UUID sourceId) {
        if (!sourceRepository.existsById(sourceId)) {
            throw new NoSuchElementException("Migration source not found: " + sourceId);
        }
        return batchRepository.findByMigrationSourceIdOrderByBatchNumberDesc(sourceId).stream()
                .map(this::toBatchResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public MigrationBatchResponse getBatchById(UUID id) {
        MigrationBatch batch = batchRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Migration batch not found: " + id));
        return toBatchResponse(batch);
    }

    public MigrationBatchResponse createBatch(UUID sourceId, MigrationBatchCreateRequest request) {
        MigrationSource source = sourceRepository.findById(sourceId)
                .orElseThrow(() -> new NoSuchElementException("Migration source not found: " + sourceId));
        if (!source.getIsActive()) {
            throw new IllegalStateException("لا يمكن إنشاء دفعة لمصدر ترحيل غير نشط");
        }

        String batchNumber = request.batchNumber().trim();
        if (batchRepository.existsByMigrationSourceIdAndBatchNumber(sourceId, batchNumber)) {
            throw new IllegalArgumentException(
                    "Migration batch number already exists for source: " + batchNumber);
        }

        MigrationBatch batch = new MigrationBatch();
        batch.setMigrationSourceId(sourceId);
        batch.setBatchNumber(batchNumber);
        batch.setNotes(request.notes());
        batch.setProjectsCount(0);
        batch.setFilesCount(0);
        batch.setStatus(MigrationBatch.Status.PLANNED);

        MigrationBatch saved = batchRepository.save(batch);
        log.info("Created migration batch {} for source {}", saved.getId(), sourceId);
        return toBatchResponse(saved);
    }

    public MigrationBatchResponse updateBatch(UUID id, MigrationBatchUpdateRequest request) {
        MigrationBatch batch = batchRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Migration batch not found: " + id));

        if (batch.getStatus() == MigrationBatch.Status.RUNNING
                || batch.getStatus() == MigrationBatch.Status.SUCCESS) {
            throw new IllegalStateException(
                    "Migration batch cannot be edited while status is " + batch.getStatus());
        }

        if (request.notes() != null) {
            batch.setNotes(request.notes());
        }

        MigrationBatch saved = batchRepository.save(batch);
        log.info("Updated migration batch: {}", saved.getId());
        return toBatchResponse(saved);
    }

    public MigrationBatchResponse runBatch(UUID batchId, MigrationRunRequest runRequest) {
        MigrationBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new NoSuchElementException("Migration batch not found: " + batchId));

        validateSupportedRunConfiguration(batch, runRequest);

        if (batch.getStatus() != MigrationBatch.Status.PLANNED
                && batch.getStatus() != MigrationBatch.Status.FAILED
                && batch.getStatus() != MigrationBatch.Status.CANCELLED) {
            throw new IllegalStateException(
                    "Migration batch can only run from PLANNED, FAILED or CANCELLED status; current status: "
                            + batch.getStatus());
        }

        if (batchRepository.existsByStatus(MigrationBatch.Status.RUNNING)) {
            throw new IllegalStateException(
                    "Another migration batch is already RUNNING");
        }

        batch.setStatus(MigrationBatch.Status.RUNNING);
        batch.setStartedAt(LocalDateTime.now());
        batch.setCompletedAt(null);
        batch.setCancelRequested(false);
        MigrationBatch saved = batchRepository.saveAndFlush(batch);

        // Do not log connection strings or other migration credentials.
        log.info("Migration batch {} accepted for background execution via legacy staging adapter",
                batchId);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    migrationExecutionService.prepare(batchId);
                    migrationExecutionService.execute(batchId);
                }
            });
        } else {
            migrationExecutionService.prepare(batchId);
            migrationExecutionService.execute(batchId);
        }

        return toBatchResponse(saved);
    }

    /**
     * Requests cancellation without interrupting the active file upload or database transaction.
     * If no local executor is registered, the RUNNING row is stale in this single-runner
     * deployment and can be finalized as CANCELLED while preserving all item tracking rows.
     */
    public MigrationBatchResponse stopBatch(UUID batchId) {
        MigrationBatch batch = batchRepository.findByIdForUpdate(batchId)
                .orElseThrow(() -> new NoSuchElementException("Migration batch not found: " + batchId));

        if (batch.getStatus() == MigrationBatch.Status.CANCELLED) {
            return toBatchResponse(batch);
        }
        if (batch.getStatus() != MigrationBatch.Status.RUNNING) {
            throw new IllegalStateException(
                    "Only a RUNNING migration batch can be stopped; current status: " + batch.getStatus());
        }

        if (migrationExecutionService.requestCancellation(batchId)) {
            int updated = batchRepository.markCancellationRequested(batchId, MigrationBatch.Status.RUNNING);
            if (updated == 0) {
                MigrationBatch latest = batchRepository.findById(batchId)
                        .orElseThrow(() -> new NoSuchElementException("Migration batch not found: " + batchId));
                if (latest.getStatus() == MigrationBatch.Status.CANCELLED) {
                    return toBatchResponse(latest);
                }
                throw new IllegalStateException(
                        "The migration finished before the stop request was accepted. Refresh the batch status.");
            }
            // Bulk update avoids saving this stale entity over the worker's final status.
            return toBatchResponse(batch, true);
        }

        // The locked row is RUNNING but no local executor is registered. In this single-runner
        // deployment this is an abandoned RUNNING record, typically left by an application restart.
        // Preserve all item mappings and make any interrupted item retryable.
        itemRepository.markAbandonedRunningItemsFailed(
                batch.getMigrationSourceId(),
                com.centralArchiveSystem.Archive.import_.migration.legacy.LegacyMigrationItem.Status.RUNNING,
                com.centralArchiveSystem.Archive.import_.migration.legacy.LegacyMigrationItem.Status.FAILED,
                LocalDateTime.now(),
                "The migration process stopped before this item completed; retry it when resuming the batch.");
        refreshBatchCounts(batch);
        batch.setStatus(MigrationBatch.Status.CANCELLED);
        batch.setCancelRequested(false);
        batch.setCompletedAt(LocalDateTime.now());
        batch.setNotes(appendNote(batch.getNotes(),
                "CANCELLED: no active executor was registered; interrupted item rows were marked FAILED for retry."));
        MigrationBatch saved = batchRepository.saveAndFlush(batch);
        return toBatchResponse(saved);
    }

    private void refreshBatchCounts(MigrationBatch batch) {
        UUID sourceId = batch.getMigrationSourceId();
        long doneProjects = itemRepository.countByMigrationSourceIdAndKindAndStatusAndSourceKeyStartingWith(
                sourceId, com.centralArchiveSystem.Archive.import_.migration.legacy.LegacyMigrationItem.Kind.PROJECT,
                com.centralArchiveSystem.Archive.import_.migration.legacy.LegacyMigrationItem.Status.DONE, "PROJECT-");
        long doneFiles = itemRepository.countByMigrationSourceIdAndKindAndStatus(
                sourceId, com.centralArchiveSystem.Archive.import_.migration.legacy.LegacyMigrationItem.Kind.FILE,
                com.centralArchiveSystem.Archive.import_.migration.legacy.LegacyMigrationItem.Status.DONE);
        long trackedProjects = trackedItemCount(
                sourceId, com.centralArchiveSystem.Archive.import_.migration.legacy.LegacyMigrationItem.Kind.PROJECT, "PROJECT-");
        long trackedFiles = trackedItemCount(
                sourceId, com.centralArchiveSystem.Archive.import_.migration.legacy.LegacyMigrationItem.Kind.FILE, null);
        long trackedRemainingProjects = remainingCount(
                sourceId, com.centralArchiveSystem.Archive.import_.migration.legacy.LegacyMigrationItem.Kind.PROJECT, "PROJECT-");
        long trackedRemainingFiles = remainingCount(
                sourceId, com.centralArchiveSystem.Archive.import_.migration.legacy.LegacyMigrationItem.Kind.FILE, null);

        batch.setProjectsCount(Math.toIntExact(doneProjects));
        batch.setFilesCount(Math.toIntExact(doneFiles));
        // If the original source is offline after a process restart, retain known totals and at
        // least report all durable tracking rows that are not DONE.
        batch.setTotalProjectsCount(Math.max(batch.getTotalProjectsCount(), Math.toIntExact(trackedProjects)));
        batch.setTotalFilesCount(Math.max(batch.getTotalFilesCount(), Math.toIntExact(trackedFiles)));
        batch.setRemainingProjectsCount(Math.max(
                Math.max(0, batch.getTotalProjectsCount() - batch.getProjectsCount()),
                Math.toIntExact(trackedRemainingProjects)));
        batch.setRemainingFilesCount(Math.max(
                Math.max(0, batch.getTotalFilesCount() - batch.getFilesCount()),
                Math.toIntExact(trackedRemainingFiles)));
    }

    private long remainingCount(
            UUID sourceId,
            com.centralArchiveSystem.Archive.import_.migration.legacy.LegacyMigrationItem.Kind kind,
            String sourcePrefix) {
        long count = 0;
        for (com.centralArchiveSystem.Archive.import_.migration.legacy.LegacyMigrationItem.Status status
                : com.centralArchiveSystem.Archive.import_.migration.legacy.LegacyMigrationItem.Status.values()) {
            if (status == com.centralArchiveSystem.Archive.import_.migration.legacy.LegacyMigrationItem.Status.DONE) {
                continue;
            }
            count += sourcePrefix == null
                    ? itemRepository.countByMigrationSourceIdAndKindAndStatus(sourceId, kind, status)
                    : itemRepository.countByMigrationSourceIdAndKindAndStatusAndSourceKeyStartingWith(
                            sourceId, kind, status, sourcePrefix);
        }
        return count;
    }

    private long trackedItemCount(
            UUID sourceId,
            com.centralArchiveSystem.Archive.import_.migration.legacy.LegacyMigrationItem.Kind kind,
            String sourcePrefix) {
        long count = 0;
        for (com.centralArchiveSystem.Archive.import_.migration.legacy.LegacyMigrationItem.Status status
                : com.centralArchiveSystem.Archive.import_.migration.legacy.LegacyMigrationItem.Status.values()) {
            count += sourcePrefix == null
                    ? itemRepository.countByMigrationSourceIdAndKindAndStatus(sourceId, kind, status)
                    : itemRepository.countByMigrationSourceIdAndKindAndStatusAndSourceKeyStartingWith(
                            sourceId, kind, status, sourcePrefix);
        }
        return count;
    }

    private static String appendNote(String existing, String addition) {
        if (existing == null || existing.isBlank()) return addition;
        return existing + System.lineSeparator() + addition;
    }

    private void validateSupportedRunConfiguration(
            MigrationBatch batch,
            MigrationRunRequest runRequest) {
        if (!migrationSettingsService.isMigrationEnabled()) {
            throw new IllegalStateException(
                    "Legacy migration is disabled. Enable it from the migration settings screen, "
                            + "or set LEGACY_MIGRATION_ENABLED=true, only during a controlled migration window");
        }

        MigrationSource source = sourceRepository.findById(batch.getMigrationSourceId())
                .orElseThrow(() -> new NoSuchElementException(
                        "Migration source not found: " + batch.getMigrationSourceId()));
        if (!source.getIsActive()) {
            throw new IllegalStateException("لا يمكن تشغيل دفعة من مصدر ترحيل غير نشط");
        }

        MigrationRunRequest.MigrationConfig config = runRequest.migration_config();

        // The connection comes from the source record, so each organisation is read from its own
        // database. A source with no stored connection still falls back to the environment default,
        // which keeps a single-database setup working without extra configuration.
        String office = source.getOfficeName();
        if (office == null || office.isBlank()) {
            throw new IllegalStateException(
                    "Migration source " + source.getId() + " has no office name. It is required because it "
                            + "scopes every migrated id, otherwise two offices would collide on the same "
                            + "legacy project numbers.");
        }

        if (source.hasStoredConnection() && source.getPort() == null) {
            throw new IllegalStateException(
                    "Migration source " + source.getId() + " stores a host but no port. Set the port on the source.");
        }

        if (config.connection_string() != null && !config.connection_string().isBlank()) {
            // Rejected rather than ignored, so nobody believes a URL typed into the run dialog is
            // being used. The connection belongs to the source record, which is encrypted at rest.
            throw new IllegalArgumentException(
                    "A connection string cannot be supplied when running a batch. The connection is read "
                            + "from migration source " + source.getId() + " (office " + office + "). "
                            + "Configure host, port, database and credentials on the source instead.");
        }

        if (config.file_path() != null && !config.file_path().isBlank()) {
            throw new IllegalArgumentException(
                    "File-based migration adapter is not implemented in the current MVP");
        }

        if (config.project_type_mapping() != null && !config.project_type_mapping().isEmpty()) {
            throw new IllegalArgumentException(
                    "Dynamic project type mappings are not implemented in the current MVP");
        }

        if (config.default_confidentiality_level_id() != null) {
            throw new IllegalArgumentException(
                    "Default confidentiality override is not implemented in the current MVP");
        }

        // The source type is no longer checked here. It used to be two equalsIgnoreCase calls at
        // this point, which meant an unsupported type was accepted on write and only rejected once
        // somebody aimed a migration window at it. It is now MigrationSourceType, so a type outside
        // the enum cannot be stored, and a source saved before this change is covered by the
        // constraint in V20. Rejecting it again here would only restate the enum.
    }

    public MigrationBatchResponse completeBatch(
            UUID batchId,
            MigrationBatchCompleteRequest request) {
        MigrationBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new NoSuchElementException("Migration batch not found: " + batchId));

        if (request.status() != MigrationBatch.Status.SUCCESS
                && request.status() != MigrationBatch.Status.PARTIAL
                && request.status() != MigrationBatch.Status.FAILED) {
            throw new IllegalArgumentException(
                    "Completion status must be SUCCESS, PARTIAL or FAILED");
        }

        if (batch.getStatus() == MigrationBatch.Status.RUNNING) {
            throw new IllegalStateException(
                    "A running batch can only be completed by its migration worker or stopped safely.");
        }

        batch.setCompletedAt(LocalDateTime.now());
        batch.setStatus(request.status());
        if (request.notes() != null) {
            batch.setNotes(request.notes());
        }

        MigrationBatch saved = batchRepository.save(batch);
        log.info("Completed migration batch {} status={}", batchId, request.status());
        return toBatchResponse(saved);
    }

    private MigrationSourceResponse toSourceResponse(MigrationSource entity) {
        return new MigrationSourceResponse(
                entity.getId(),
                entity.getName(),
                entity.getSourceType(),
                entity.getDatabaseName(),
                entity.getOfficeName(),
                entity.getHost(),
                entity.getPort(),
                entity.getUsername(),
                entity.hasStoredConnection(),
                entity.getDescription(),
                entity.getIsActive(),
                batchRepository.countByMigrationSourceId(entity.getId()),
                entity.getCreatedAt()
        );
    }

    private MigrationBatchResponse toBatchResponse(MigrationBatch entity) {
        return new MigrationBatchResponse(
                entity.getId(),
                entity.getMigrationSourceId(),
                entity.getBatchNumber(),
                entity.getStartedAt(),
                entity.getCompletedAt(),
                entity.getStatus().name(),
                entity.getProjectsCount(),
                entity.getFilesCount(),
                entity.getTotalProjectsCount(),
                entity.getTotalFilesCount(),
                entity.getRemainingProjectsCount(),
                entity.getRemainingFilesCount(),
                entity.isCancelRequested(),
                entity.getNotes()
        );
    }

    private MigrationBatchResponse toBatchResponse(MigrationBatch entity, boolean cancelRequested) {
        return new MigrationBatchResponse(
                entity.getId(),
                entity.getMigrationSourceId(),
                entity.getBatchNumber(),
                entity.getStartedAt(),
                entity.getCompletedAt(),
                entity.getStatus().name(),
                entity.getProjectsCount(),
                entity.getFilesCount(),
                entity.getTotalProjectsCount(),
                entity.getTotalFilesCount(),
                entity.getRemainingProjectsCount(),
                entity.getRemainingFilesCount(),
                cancelRequested,
                entity.getNotes()
        );
    }
}
