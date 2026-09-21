package com.centralArchiveSystem.Archive.import_.package_.export.export;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProjectExportRepository extends JpaRepository<ProjectExport, UUID> {

    Optional<ProjectExport> findByPackageUuid(UUID packageUuid);

    List<ProjectExport> findBySourceOrganizationIdOrderByCreatedAtDesc(UUID sourceOrganizationId);
}
