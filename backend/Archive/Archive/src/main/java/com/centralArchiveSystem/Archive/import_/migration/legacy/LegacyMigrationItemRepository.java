package com.centralArchiveSystem.Archive.import_.migration.legacy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LegacyMigrationItemRepository extends JpaRepository<LegacyMigrationItem, UUID> {

    // Every lookup is scoped by source on purpose. Unscoped variants would mix organisations
    // together and make a row from one office look like it had already been migrated.
    Optional<LegacyMigrationItem> findByMigrationSourceIdAndKindAndSourceKey(
            UUID migrationSourceId, LegacyMigrationItem.Kind kind, String sourceKey);

    /**
     * Look up a bounded page of a source's file tracking rows in one query.
     * Never look up source keys without migrationSourceId: different office
     * databases reuse the same legacy file IDs.
     */
    List<LegacyMigrationItem> findByMigrationSourceIdAndKindAndSourceKeyIn(
            UUID migrationSourceId, LegacyMigrationItem.Kind kind, List<String> sourceKeys);

    boolean existsByMigrationSourceIdAndKindAndSourceKey(
            UUID migrationSourceId, LegacyMigrationItem.Kind kind, String sourceKey);

    List<LegacyMigrationItem> findByMigrationSourceIdAndKindAndStatus(
            UUID migrationSourceId, LegacyMigrationItem.Kind kind, LegacyMigrationItem.Status status);

    long countByMigrationSourceIdAndKindAndStatus(
            UUID migrationSourceId, LegacyMigrationItem.Kind kind, LegacyMigrationItem.Status status);

    long countByMigrationSourceIdAndKindAndStatusAndSourceKeyStartingWith(
            UUID migrationSourceId, LegacyMigrationItem.Kind kind, LegacyMigrationItem.Status status,
            String sourceKeyPrefix);

    long countByMigrationSourceIdAndStatus(UUID migrationSourceId, LegacyMigrationItem.Status status);

    long countByMigrationSourceId(UUID migrationSourceId);

    /**
     * Database-side audit; does not fetch file bytes or mutate tracking items.
     * Count DONE entries whose target file and project metadata is no longer valid.
     */
    @Query(value = """
        SELECT i.status, COUNT(*),
               COUNT(*) FILTER (
                 WHERE i.status = 'DONE' AND (
                   i.target_id IS NULL OR f.id IS NULL OR p.id IS NULL
                   OR f.deleted_at IS NOT NULL OR UPPER(f.status) NOT IN ('ACTIVE', 'QUARANTINED')
                 ))
        FROM import.legacy_migration_items i
        LEFT JOIN project.files f ON f.id = i.target_id
        LEFT JOIN project.projects p ON p.id = f.project_id
        WHERE i.migration_source_id = :sourceId AND i.kind = 'FILE'
        GROUP BY i.status
        """, nativeQuery = true)
    List<Object[]> auditFileLinkCounts(@Param("sourceId") UUID sourceId);

    @Query(value = """
        SELECT i.source_key, i.status, i.target_id,
               CASE
                 WHEN i.status <> 'DONE' THEN COALESCE(i.error, 'Not completed')
                 WHEN i.target_id IS NULL THEN 'Missing target_id'
                 WHEN f.id IS NULL THEN 'File metadata row is missing'
                 WHEN p.id IS NULL THEN 'Target project row is missing'
                 WHEN f.deleted_at IS NOT NULL OR UPPER(f.status) NOT IN ('ACTIVE', 'QUARANTINED')
                   THEN 'File is deleted or inactive'
                 ELSE 'Unknown'
               END
        FROM import.legacy_migration_items i
        LEFT JOIN project.files f ON f.id = i.target_id
        LEFT JOIN project.projects p ON p.id = f.project_id
        WHERE i.migration_source_id = :sourceId AND i.kind = 'FILE'
          AND (i.status <> 'DONE' OR i.target_id IS NULL OR f.id IS NULL
               OR p.id IS NULL OR f.deleted_at IS NOT NULL OR UPPER(f.status) NOT IN ('ACTIVE', 'QUARANTINED'))
        ORDER BY i.source_key
        LIMIT 50
        """, nativeQuery = true)
    List<Object[]> sampleFileLinkProblems(@Param("sourceId") UUID sourceId);

    @Query(value = """
        SELECT i.source_key, f.id, f.bucket_name, f.object_key
        FROM import.legacy_migration_items i
        JOIN project.files f ON f.id = i.target_id
        JOIN project.projects p ON p.id = f.project_id
        WHERE i.migration_source_id = :sourceId AND i.kind = 'FILE'
          AND i.status = 'DONE' AND f.deleted_at IS NULL
          AND UPPER(f.status) IN ('ACTIVE', 'QUARANTINED')
        ORDER BY i.source_key
        LIMIT 15
        """, nativeQuery = true)
    List<Object[]> sampleLinkedStorageObjects(@Param("sourceId") UUID sourceId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update LegacyMigrationItem item set item.status = :failedStatus, "
            + "item.error = :error, item.finishedAt = :finishedAt "
            + "where item.migrationSourceId = :sourceId and item.status = :runningStatus")
    int markAbandonedRunningItemsFailed(
            @Param("sourceId") UUID sourceId,
            @Param("runningStatus") LegacyMigrationItem.Status runningStatus,
            @Param("failedStatus") LegacyMigrationItem.Status failedStatus,
            @Param("finishedAt") LocalDateTime finishedAt,
            @Param("error") String error);
}
