package com.centralArchiveSystem.Archive.import_.migration;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MigrationSourceRepository extends JpaRepository<MigrationSource, UUID> {

    List<MigrationSource> findBySourceType(String sourceType);

    List<MigrationSource> findByOfficeName(String officeName);

    List<MigrationSource> findByNameContainingIgnoreCase(String name);
}
