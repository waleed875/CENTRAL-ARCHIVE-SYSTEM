package com.centralArchiveSystem.Archive.import_.operation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ImportOperationRepository extends JpaRepository<ImportOperation, UUID> {

    List<ImportOperation> findByPackageIdOrderByStartedAtDesc(UUID packageId);

    List<ImportOperation> findByImportedByOrderByStartedAtDesc(UUID importedBy);

    List<ImportOperation> findByStatus(ImportOperation.Status status);
}
