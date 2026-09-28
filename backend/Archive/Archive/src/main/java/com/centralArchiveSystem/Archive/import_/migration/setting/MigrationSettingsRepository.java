package com.centralArchiveSystem.Archive.import_.migration.setting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MigrationSettingsRepository extends JpaRepository<MigrationSettings, UUID> {

    /** The table is a singleton, so there is never more than one candidate. */
    Optional<MigrationSettings> findFirstBySingletonTrue();
}
