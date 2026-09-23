package com.centralArchiveSystem.Archive.import_.operation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ImportOperationService {

    public enum ProjectOutcome {
        IMPORTED, PARTIAL, FAILED, DUPLICATE, SKIPPED
    }

    private static final Logger log = LoggerFactory.getLogger(ImportOperationService.class);
    private static final String RESTART_INTERRUPTION_MESSAGE =
            "Import interrupted by application restart before completion";

    private final ImportOperationRepository repository;

    public ImportOperationService(ImportOperationRepository repository) {
        this.repository = repository;
    }

    public ImportOperation create(UUID packageId, UUID importedBy) {
        return create(packageId, importedBy, null);
    }

    public ImportOperation create(UUID packageId, UUID importedBy, String requestSnapshot) {
        if (repository.existsByPackageIdAndStatus(packageId, ImportOperation.Status.STARTED)) {
            throw new IllegalStateException(
                    "An import operation is already running for package: " + packageId);
        }

        ImportOperation operation = new ImportOperation(packageId, importedBy);
        operation.setRequestSnapshot(requestSnapshot);

        repository.findFirstByPackageIdOrderByStartedAtDesc(packageId)
                .filter(previous -> previous.getStatus() == ImportOperation.Status.INTERRUPTED
                        || previous.getStatus() == ImportOperation.Status.FAILED
                        || previous.getStatus() == ImportOperation.Status.PARTIAL)
                .ifPresent(previous -> operation.setRetryOf(previous.getId()));

        ImportOperation saved = repository.save(operation);
        log.info("Created import operation: id={}, packageId={}, importedBy={}, retryOf={}",
                saved.getId(), packageId, importedBy, saved.getRetryOf());
        return saved;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ImportOperation heartbeat(UUID id) {
        ImportOperation operation = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Import operation not found: " + id));

        if (operation.getStatus() != ImportOperation.Status.STARTED) {
            return operation;
        }

        operation.setLastHeartbeatAt(LocalDateTime.now());
        return repository.save(operation);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ImportOperation initializeProjectStatistics(UUID id, int totalProjects) {
        ImportOperation operation = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Import operation not found: " + id));
        operation.setProjectsTotal(totalProjects);
        return repository.save(operation);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ImportOperation recordProjectOutcome(UUID id, ProjectOutcome outcome) {
        ImportOperation operation = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Import operation not found: " + id));

        switch (outcome) {
            case IMPORTED -> operation.setProjectsImported(operation.getProjectsImported() + 1);
            case PARTIAL -> operation.setProjectsPartial(operation.getProjectsPartial() + 1);
            case FAILED -> operation.setProjectsFailed(operation.getProjectsFailed() + 1);
            case DUPLICATE -> operation.setProjectsDuplicate(operation.getProjectsDuplicate() + 1);
            case SKIPPED -> operation.setProjectsSkipped(operation.getProjectsSkipped() + 1);
        }
        operation.setLastHeartbeatAt(LocalDateTime.now());
        return repository.save(operation);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ImportOperation updateStatus(UUID id, ImportOperation.Status status, String errorMessage) {
        ImportOperation operation = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Import operation not found: " + id));

        operation.setStatus(status);
        operation.setLastHeartbeatAt(LocalDateTime.now());
        if (status == ImportOperation.Status.SUCCESS
                || status == ImportOperation.Status.FAILED
                || status == ImportOperation.Status.PARTIAL
                || status == ImportOperation.Status.INTERRUPTED) {
            operation.setCompletedAt(LocalDateTime.now());
        }
        if (errorMessage != null) {
            operation.setErrorMessage(errorMessage);
        }

        ImportOperation saved = repository.save(operation);
        log.info("Updated import operation: id={}, status={}", saved.getId(), status);
        return saved;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedOperationsAfterRestart() {
        List<ImportOperation> started = repository.findByStatus(ImportOperation.Status.STARTED);
        if (started.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        for (ImportOperation operation : started) {
            operation.setStatus(ImportOperation.Status.INTERRUPTED);
            operation.setLastHeartbeatAt(now);
            operation.setCompletedAt(now);
            operation.setErrorMessage(RESTART_INTERRUPTION_MESSAGE);
        }
        repository.saveAll(started);
        log.warn("Recovered {} interrupted package import operation(s) after restart", started.size());
    }

    @Transactional(readOnly = true)
    public List<ImportOperation> getByPackageId(UUID packageId) {
        return repository.findByPackageIdOrderByStartedAtDesc(packageId);
    }

    @Transactional(readOnly = true)
    public ImportOperation getById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Import operation not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<ImportOperation> getByImportedBy(UUID importedBy) {
        return repository.findByImportedByOrderByStartedAtDesc(importedBy);
    }

    @Transactional(readOnly = true)
    public List<ImportOperation> getByStatus(ImportOperation.Status status) {
        return repository.findByStatus(status);
    }
}
