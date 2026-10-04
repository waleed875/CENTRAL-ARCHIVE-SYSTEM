package com.centralArchiveSystem.Archive.import_.package_;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PackageProjectRepository extends JpaRepository<PackageProject, UUID> {

    List<PackageProject> findByPackageId(UUID packageId);

    Page<PackageProject> findByPackageId(UUID packageId, Pageable pageable);

    Optional<PackageProject> findByPackageIdAndProjectId(UUID packageId, UUID projectId);

    List<PackageProject> findByPackageIdAndImportStatus(
            UUID packageId, PackageProject.ImportStatus importStatus);

    long countByPackageId(UUID packageId);

    long countByPackageIdAndImportStatus(
            UUID packageId, PackageProject.ImportStatus importStatus);

    Optional<PackageProject> findByPackageIdAndLocalProjectUuid(
            UUID packageId, UUID localProjectUuid);

    List<PackageProject> findByLocalProjectUuid(UUID localProjectUuid);

    /** The FK is {@code package_projects.project_id}, so an incoming link blocks removal. */
    boolean existsByProjectId(UUID projectId);
}
