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
public interface BranchPackageRepository extends JpaRepository<BranchPackage, UUID> {

    Optional<BranchPackage> findByPackageUuid(String packageUuid);

    Optional<BranchPackage> findByPackageHash(String packageHash);

    boolean existsByPackageHash(String packageHash);

    boolean existsByPackageUuid(String packageUuid);

    Page<BranchPackage> findByStatus(BranchPackage.PackageStatus status, Pageable pageable);

    Page<BranchPackage> findBySourceOrganizationId(UUID sourceOrganizationId, Pageable pageable);

    Page<BranchPackage> findBySourceOrganizationIdAndStatus(
            UUID sourceOrganizationId, BranchPackage.PackageStatus status, Pageable pageable);

    @Query("SELECT bp FROM BranchPackage bp WHERE " +
           "(:searchTerm IS NULL OR :searchTerm = '' OR " +
           "LOWER(bp.packageName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(bp.packageUuid) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(bp.sourceUsername) LIKE LOWER(CONCAT('%', :searchTerm, '%'))) " +
           "AND (:status IS NULL OR bp.status = :status) " +
           "AND (:sourceOrganizationId IS NULL OR bp.sourceOrganizationId = :sourceOrganizationId)")
    Page<BranchPackage> search(
            @Param("searchTerm") String searchTerm,
            @Param("status") BranchPackage.PackageStatus status,
            @Param("sourceOrganizationId") UUID sourceOrganizationId,
            Pageable pageable);

    List<BranchPackage> findByStatus(BranchPackage.PackageStatus status);

    long countByStatus(BranchPackage.PackageStatus status);

    long countBySourceOrganizationId(UUID sourceOrganizationId);
}
