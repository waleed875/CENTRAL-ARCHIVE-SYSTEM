package com.centralArchiveSystem.Archive.import_.migration.legacy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LegacyMigrationItemRepository extends JpaRepository<LegacyMigrationItem, UUID> {

    List<LegacyMigrationItem> findByKindAndStatus(LegacyMigrationItem.Kind kind, LegacyMigrationItem.Status status);

    long countByKindAndStatus(LegacyMigrationItem.Kind kind, LegacyMigrationItem.Status status);

    Optional<LegacyMigrationItem> findByKindAndSourceKey(LegacyMigrationItem.Kind kind, String sourceKey);

    boolean existsByKindAndSourceKey(LegacyMigrationItem.Kind kind, String sourceKey);
}
