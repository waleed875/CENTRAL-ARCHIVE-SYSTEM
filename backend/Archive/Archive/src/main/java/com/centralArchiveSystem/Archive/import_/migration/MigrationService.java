package com.centralArchiveSystem.Archive.import_.migration;

import com.centralArchiveSystem.Archive.import_.migration.dto.*;
import com.centralArchiveSystem.Archive.import_.migration.legacy.MigrationRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class MigrationService {

    private static final Logger log = LoggerFactory.getLogger(MigrationService.class);

    private final MigrationSourceRepository sourceRepository;
    private final MigrationBatchRepository batchRepository;
    private final MigrationExecutionService migrationExecutionService;

    public MigrationService(MigrationSourceRepository sourceRepository,
                            MigrationBatchRepository batchRepository,
                            MigrationExecutionService migrationExecutionService) {
        this.sourceRepository = sourceRepository;
        this.batchRepository = batchRepository;
        this.migrationExecutionService = migrationExecutionService;
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

    public MigrationBatchResponse createBatch(UUID sourceId, MigrationBatchCreateRequest request) {
        if (!sourceRepository.existsById(sourceId)) {
            throw new RuntimeException("Migration source not found: " + sourceId);
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
                .orElseThrow(() -> new RuntimeException("Migration batch not found: " + id));

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
                .orElseThrow(() -> new RuntimeException("Migration batch not found: " + batchId));

        if (batch.getStatus() != MigrationBatch.Status.PLANNED
                && batch.getStatus() != MigrationBatch.Status.FAILED) {
            throw new IllegalStateException(
                    "Migration batch can only run from PLANNED or FAILED status; current status: "
                            + batch.getStatus());
        }

        batch.setStatus(MigrationBatch.Status.RUNNING);
        batch.setStartedAt(LocalDateTime.now());
        batch.setCompletedAt(null);
        MigrationBatch saved = batchRepository.save(batch);

        // Do not log connection strings or other migration credentials.
        log.info("Migration batch {} accepted for background execution, type={}",
                batchId, runRequest.type());

        migrationExecutionService.execute(batchId);
        return toBatchResponse(saved);
    }

    public MigrationBatchResponse completeBatch(
            UUID batchId,
            MigrationBatchCompleteRequest request) {
        MigrationBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new RuntimeException("Migration batch not found: " + batchId));

        if (request.status() != MigrationBatch.Status.SUCCESS
                && request.status() != MigrationBatch.Status.PARTIAL
                && request.status() != MigrationBatch.Status.FAILED) {
            throw new IllegalArgumentException(
                    "Completion status must be SUCCESS, PARTIAL or FAILED");
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
                entity.getDescription(),
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
                entity.getNotes()
        );
    }
}
