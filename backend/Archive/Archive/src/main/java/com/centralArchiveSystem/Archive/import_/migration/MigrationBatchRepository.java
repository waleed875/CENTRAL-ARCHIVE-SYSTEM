package com.centralArchiveSystem.Archive.import_.migration;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MigrationBatchRepository extends JpaRepository<MigrationBatch, UUID> {

    List<MigrationBatch> findByMigrationSourceIdOrderByBatchNumberDesc(UUID migrationSourceId);

    List<MigrationBatch> findByStatus(MigrationBatch.Status status);

    Long countByMigrationSourceIdAndStatus(UUID migrationSourceId, MigrationBatch.Status status);

    long countByMigrationSourceId(UUID migrationSourceId);

    boolean existsByMigrationSourceIdAndBatchNumber(UUID migrationSourceId, String batchNumber);

    MigrationBatch findTopByMigrationSourceIdOrderByBatchNumberDesc(UUID migrationSourceId);
}
