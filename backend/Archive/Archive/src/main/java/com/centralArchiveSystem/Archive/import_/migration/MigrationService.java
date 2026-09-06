package com.centralArchiveSystem.Archive.import_.migration;

import com.centralArchiveSystem.Archive.import_.migration.dto.*;
import com.centralArchiveSystem.Archive.import_.migration.legacy.MigrationRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
@Transactional
public class MigrationService {

    private static final Logger log = LoggerFactory.getLogger(MigrationService.class);

    private final MigrationSourceRepository sourceRepository;
    private final MigrationBatchRepository batchRepository;
    private final MigrationRunner migrationRunner;

    public MigrationService(MigrationSourceRepository sourceRepository,
                            MigrationBatchRepository batchRepository,
                            MigrationRunner migrationRunner) {
        this.sourceRepository = sourceRepository;
        this.batchRepository = batchRepository;
        this.migrationRunner = migrationRunner;
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
                .orElseThrow(() -> new RuntimeException("Migration source not found: " + id));
        return toSourceResponse(source);
    }

    public MigrationSourceResponse createSource(MigrationSourceRequest request) {
        MigrationSource source = new MigrationSource();
        source.setName(request.name());
        source.setSourceType(request.sourceType());
        source.setDatabaseName(request.databaseName());
        source.setOfficeName(request.officeName());
        source.setDescription(request.description());
        MigrationSource saved = sourceRepository.save(source);
        log.info("Created migration source: {}", saved.getId());
        return toSourceResponse(saved);
    }

    public MigrationSourceResponse updateSource(UUID id, MigrationSourceRequest request) {
        MigrationSource source = sourceRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Migration source not found: " + id));
        if (request.name() != null) source.setName(request.name());
        if (request.sourceType() != null) source.setSourceType(request.sourceType());
        if (request.databaseName() != null) source.setDatabaseName(request.databaseName());
        if (request.officeName() != null) source.setOfficeName(request.officeName());
        if (request.description() != null) source.setDescription(request.description());
        MigrationSource saved = sourceRepository.save(source);
        log.info("Updated migration source: {}", saved.getId());
        return toSourceResponse(saved);
    }

    public void deleteSource(UUID id) {
        if (!sourceRepository.existsById(id)) {
            throw new RuntimeException("Migration source not found: " + id);
        }
        sourceRepository.deleteById(id);
        log.info("Deleted migration source: {}", id);
    }

    @Transactional(readOnly = true)
    public List<MigrationBatchResponse> listBatches(UUID sourceId) {
        return batchRepository.findByMigrationSourceIdOrderByBatchNumberDesc(sourceId).stream()
                .map(this::toBatchResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public MigrationBatchResponse getBatchById(UUID id) {
        MigrationBatch batch = batchRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Migration batch not found: " + id));
        return toBatchResponse(batch);
    }

    public MigrationBatchResponse createBatch(UUID sourceId, MigrationBatchRequest request) {
        if (!sourceRepository.existsById(sourceId)) {
            throw new RuntimeException("Migration source not found: " + sourceId);
        }
        MigrationBatch lastBatch = batchRepository.findTopByMigrationSourceIdOrderByBatchNumberDesc(sourceId);
        int nextNumber = (lastBatch != null) ? lastBatch.getBatchNumber() + 1 : 1;

        MigrationBatch batch = new MigrationBatch();
        batch.setMigrationSourceId(sourceId);
        batch.setBatchNumber(nextNumber);
        batch.setNotes(request.notes());
        batch.setProjectsCount(request.projectsCount() != null ? request.projectsCount() : 0);
        batch.setFilesCount(request.filesCount() != null ? request.filesCount() : 0);
        batch.setStatus(MigrationBatch.Status.PLANNED);

        MigrationBatch saved = batchRepository.save(batch);
        log.info("Created migration batch {} for source {}", saved.getId(), sourceId);
        return toBatchResponse(saved);
    }

    public MigrationBatchResponse updateBatch(UUID id, MigrationBatchRequest request) {
        MigrationBatch batch = batchRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Migration batch not found: " + id));
        if (request.notes() != null) batch.setNotes(request.notes());
        if (request.projectsCount() != null) batch.setProjectsCount(request.projectsCount());
        if (request.filesCount() != null) batch.setFilesCount(request.filesCount());
        MigrationBatch saved = batchRepository.save(batch);
        log.info("Updated migration batch: {}", saved.getId());
        return toBatchResponse(saved);
    }

    @Async
    public CompletableFuture<Void> runBatch(UUID batchId, MigrationRunRequest runRequest) {
        MigrationBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new RuntimeException("Migration batch not found: " + batchId));

        if (batch.getStatus() == MigrationBatch.Status.RUNNING) {
            throw new RuntimeException("Batch is already running: " + batchId);
        }

        batch.setStatus(MigrationBatch.Status.RUNNING);
        batch.setMigrationSourceId(migrationRunner.ensureSourceId());
        batch.setStartedAt(LocalDateTime.now());
        batchRepository.save(batch);

        log.info("Requesting legacy migration via runner (batch {} type={} connection={})",
                batchId, runRequest.type(), runRequest.connection_string());

        boolean started = migrationRunner.runOnce();
        log.info("Migration runner executed for batch {}: started={}", batchId, started);

        return CompletableFuture.completedFuture(null);
    }

    public MigrationBatchResponse completeBatch(UUID batchId, int projectsCount, int filesCount, String status) {
        MigrationBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new RuntimeException("Migration batch not found: " + batchId));

        batch.setCompletedAt(LocalDateTime.now());
        batch.setProjectsCount(projectsCount);
        batch.setFilesCount(filesCount);
        batch.setStatus(MigrationBatch.Status.valueOf(status));

        MigrationBatch saved = batchRepository.save(batch);
        log.info("Completed migration batch {} status={}", batchId, status);
        return toBatchResponse(saved);
    }

    private MigrationSourceResponse toSourceResponse(MigrationSource entity) {
        return new MigrationSourceResponse(
                entity.getId(),
                entity.getName(),
                entity.getSourceType(),
                entity.getDatabaseName(),
                entity.getOfficeName(),
                entity.getDescription(),
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
                entity.getNotes()
        );
    }
}
