package com.centralArchiveSystem.Archive.import_.migration.legacy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LegacyMigrationItemRepository extends JpaRepository<LegacyMigrationItem, UUID> {

    // Every lookup is scoped by source on purpose. Unscoped variants would mix organisations
    // together and make a row from one office look like it had already been migrated.
    Optional<LegacyMigrationItem> findByMigrationSourceIdAndKindAndSourceKey(
            UUID migrationSourceId, LegacyMigrationItem.Kind kind, String sourceKey);

    boolean existsByMigrationSourceIdAndKindAndSourceKey(
            UUID migrationSourceId, LegacyMigrationItem.Kind kind, String sourceKey);

    List<LegacyMigrationItem> findByMigrationSourceIdAndKindAndStatus(
            UUID migrationSourceId, LegacyMigrationItem.Kind kind, LegacyMigrationItem.Status status);

    long countByMigrationSourceIdAndKindAndStatus(
            UUID migrationSourceId, LegacyMigrationItem.Kind kind, LegacyMigrationItem.Status status);

    long countByMigrationSourceIdAndStatus(UUID migrationSourceId, LegacyMigrationItem.Status status);

    long countByMigrationSourceId(UUID migrationSourceId);
}
