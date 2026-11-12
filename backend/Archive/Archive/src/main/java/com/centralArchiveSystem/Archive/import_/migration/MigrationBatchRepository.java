package com.centralArchiveSystem.Archive.import_.migration;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MigrationBatchRepository extends JpaRepository<MigrationBatch, UUID> {

    List<MigrationBatch> findByMigrationSourceIdOrderByBatchNumberDesc(UUID migrationSourceId);

    List<MigrationBatch> findByStatus(MigrationBatch.Status status);

    boolean existsByStatus(MigrationBatch.Status status);

    Long countByMigrationSourceIdAndStatus(UUID migrationSourceId, MigrationBatch.Status status);

    long countByMigrationSourceId(UUID migrationSourceId);

    boolean existsByMigrationSourceIdAndBatchNumber(UUID migrationSourceId, String batchNumber);

    MigrationBatch findTopByMigrationSourceIdOrderByBatchNumberDesc(UUID migrationSourceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from MigrationBatch b where b.id = :batchId")
    java.util.Optional<MigrationBatch> findByIdForUpdate(@Param("batchId") UUID batchId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MigrationBatch b set b.cancelRequested = true "
            + "where b.id = :batchId and b.status = :runningStatus")
    int markCancellationRequested(@Param("batchId") UUID batchId,
                                  @Param("runningStatus") MigrationBatch.Status runningStatus);
}
