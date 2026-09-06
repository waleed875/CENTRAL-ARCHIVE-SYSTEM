package com.centralArchiveSystem.Archive.import_.package_;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PackageProjectRepository extends JpaRepository<PackageProject, UUID> {

    List<PackageProject> findByPackageId(UUID packageId);

    Page<PackageProject> findByPackageId(UUID packageId, Pageable pageable);

    Optional<PackageProject> findByPackageIdAndProjectId(UUID packageId, String projectId);

    List<PackageProject> findByPackageIdAndImportStatus(
            UUID packageId, PackageProject.ImportStatus importStatus);

    long countByPackageId(UUID packageId);

    long countByPackageIdAndImportStatus(
            UUID packageId, PackageProject.ImportStatus importStatus);

    @Query("SELECT pp FROM PackageProject pp WHERE " +
           "pp.packageId = :packageId AND " +
           "pp.localProjectUuid = :localProjectUuid")
    Optional<PackageProject> findByPackageIdAndLocalProjectUuid(
            @Param("packageId") UUID packageId,
            @Param("localProjectUuid") UUID localProjectUuid);

    @Query("SELECT pp FROM PackageProject pp WHERE " +
           "pp.localProjectUuid = :localProjectUuid")
    List<PackageProject> findByLocalProjectUuid(@Param("localProjectUuid") UUID localProjectUuid);

    @Query("SELECT COUNT(pp) FROM PackageProject pp WHERE " +
           "pp.packageId = :packageId AND " +
           "pp.importStatus = :status")
    long countByPackageIdAndImportStatusParam(
            @Param("packageId") UUID packageId,
            @Param("status") PackageProject.ImportStatus status);
}
