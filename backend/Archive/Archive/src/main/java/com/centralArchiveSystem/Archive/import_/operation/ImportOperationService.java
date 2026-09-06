package com.centralArchiveSystem.Archive.import_.operation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ImportOperationService {

    private static final Logger log = LoggerFactory.getLogger(ImportOperationService.class);

    private final ImportOperationRepository repository;

    public ImportOperationService(ImportOperationRepository repository) {
        this.repository = repository;
    }

    public ImportOperation create(UUID packageId, UUID importedBy) {
        ImportOperation operation = new ImportOperation(packageId, importedBy);
        ImportOperation saved = repository.save(operation);
        log.info("Created import operation: id={}, packageId={}, importedBy={}",
                saved.getId(), packageId, importedBy);
        return saved;
    }

    public ImportOperation updateStatus(UUID id, ImportOperation.Status status, String errorMessage) {
        ImportOperation operation = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Import operation not found: " + id));

        operation.setStatus(status);
        if (status == ImportOperation.Status.SUCCESS
                || status == ImportOperation.Status.FAILED
                || status == ImportOperation.Status.PARTIAL) {
            operation.setCompletedAt(LocalDateTime.now());
        }
        if (errorMessage != null) {
            operation.setErrorMessage(errorMessage);
        }

        ImportOperation saved = repository.save(operation);
        log.info("Updated import operation: id={}, status={}", saved.getId(), status);
        return saved;
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
