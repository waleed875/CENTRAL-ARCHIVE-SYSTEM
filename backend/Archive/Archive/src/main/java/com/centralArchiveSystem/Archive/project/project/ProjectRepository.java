package com.centralArchiveSystem.Archive.project.project;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
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
public interface ProjectRepository extends JpaRepository<Project, UUID> {

    Optional<Project> findByProjectUid(UUID projectUid);

    /** Serialize imports that create the next version of the same project. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Project p WHERE p.id = :id")
    Optional<Project> lockById(@Param("id") UUID id);

    /** All versions, including confidential ones; callers MUST enforce clearance on every row. */
    List<Project> findByProjectUidOrderByVersionNoAsc(UUID projectUid);

    /**
     * Matches the projects_organization_id_project_number_year_version_no_key constraint. The legacy
     * migration checks it before inserting so a duplicate is reported instead of aborting the
     * surrounding transaction, which would discard the rows that had already migrated cleanly.
     */
    boolean existsByOrganizationIdAndProjectNumberAndYearAndVersionNo(
            UUID organizationId, String projectNumber, Integer year, Integer versionNo);

    /**
     * Whether a later version builds on this one.
     *
     * <p>Read before removing a project, because the version chain is what gives a project its
     * identity: two rows of the same chain share a {@code projectUid} and differ only by version
     * number, and {@code previous_project_id} is the link between them. Removing a version from the
     * middle leaves the later one pointing at nothing, so the chain has to be checked, not just the
     * row's own {@code versionNo}.
     */
    boolean existsByPreviousProjectId(UUID previousProjectId);

    @Query("SELECT COALESCE(MAX(p.versionNo), 0) FROM Project p WHERE p.projectUid = :projectUid")
    int findMaxVersionNoByProjectUid(@Param("projectUid") UUID projectUid);

    List<Project> findByOrganizationIdAndYear(UUID organizationId, Integer year);

    List<Project> findByStatus(Project.ProjectStatus status);

    List<Project> findByIsLatestVersion(Boolean isLatestVersion);

    Optional<Project> findByProjectNumberAndOrganizationIdAndYear(
            String projectNumber, UUID organizationId, Integer year);

    @Query("""
           SELECT p FROM Project p
           WHERE p.discardedAt IS NULL
             AND (:searchTerm IS NULL OR :searchTerm = '' OR
                  LOWER(p.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
                  LOWER(p.projectNumber) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
                  LOWER(p.projectCode) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
                  LOWER(p.description) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
                  LOWER(p.keywords) LIKE LOWER(CONCAT('%', :searchTerm, '%')))
             AND (:organizationId IS NULL OR p.organizationId = :organizationId)
             AND (:status IS NULL OR p.status = :status)
             AND (:year IS NULL OR p.year = :year)
             AND (:projectTypeId IS NULL OR p.projectTypeId = :projectTypeId)
             AND (:confidentialityLevelId IS NULL OR p.confidentialityLevelId = :confidentialityLevelId)
             AND (:sourceType IS NULL OR p.sourceType = :sourceType)
             AND (:projectUid IS NULL OR p.projectUid = :projectUid)
             AND (:createdBy IS NULL OR p.createdBy = :createdBy)
             AND (:approvedBy IS NULL OR p.approvedBy = :approvedBy)
             AND (:isLatestVersion IS NULL OR p.isLatestVersion = :isLatestVersion)
             AND (
                 p.confidentialityLevelId IS NULL
                 OR (
                     :maxLevelOrder IS NOT NULL
                     AND EXISTS (
                         SELECT c.id
                         FROM ConfidentialityLevel c
                         WHERE c.id = p.confidentialityLevelId
                           AND c.levelOrder <= :maxLevelOrder
                     )
                 )
             )
           """)
    Page<Project> searchAccessible(
            @Param("searchTerm") String searchTerm,
            @Param("organizationId") UUID organizationId,
            @Param("status") Project.ProjectStatus status,
            @Param("year") Integer year,
            @Param("projectTypeId") UUID projectTypeId,
            @Param("confidentialityLevelId") UUID confidentialityLevelId,
            @Param("sourceType") Project.SourceType sourceType,
            @Param("projectUid") UUID projectUid,
            @Param("createdBy") UUID createdBy,
            @Param("approvedBy") UUID approvedBy,
            @Param("isLatestVersion") Boolean isLatestVersion,
            @Param("maxLevelOrder") Integer maxLevelOrder,
            Pageable pageable);

    @Query("""
           SELECT p FROM Project p
           WHERE p.projectUid = :projectUid
             AND p.discardedAt IS NULL
             AND (
                 p.confidentialityLevelId IS NULL
                 OR (
                     :maxLevelOrder IS NOT NULL
                     AND EXISTS (
                         SELECT c.id
                         FROM ConfidentialityLevel c
                         WHERE c.id = p.confidentialityLevelId
                           AND c.levelOrder <= :maxLevelOrder
                     )
                 )
             )
           ORDER BY p.versionNo DESC
           """)
    List<Project> findAccessibleVersions(
            @Param("projectUid") UUID projectUid,
            @Param("maxLevelOrder") Integer maxLevelOrder);


    @Query("""
           SELECT COUNT(p) FROM Project p
           WHERE p.isLatestVersion = true
             AND p.discardedAt IS NULL
             AND (:organizationId IS NULL OR p.organizationId = :organizationId)
             AND (
                 p.confidentialityLevelId IS NULL
                 OR (
                     :maxLevelOrder IS NOT NULL
                     AND EXISTS (
                         SELECT c.id FROM ConfidentialityLevel c
                         WHERE c.id = p.confidentialityLevelId
                           AND c.levelOrder <= :maxLevelOrder
                     )
                 )
             )
           """)
    long countAccessibleLatest(
            @Param("organizationId") UUID organizationId,
            @Param("maxLevelOrder") Integer maxLevelOrder);

    @Query("""
           SELECT p.status, COUNT(p) FROM Project p
           WHERE p.isLatestVersion = true
             AND p.discardedAt IS NULL
             AND (:organizationId IS NULL OR p.organizationId = :organizationId)
             AND (
                 p.confidentialityLevelId IS NULL
                 OR (
                     :maxLevelOrder IS NOT NULL
                     AND EXISTS (
                         SELECT c.id FROM ConfidentialityLevel c
                         WHERE c.id = p.confidentialityLevelId
                           AND c.levelOrder <= :maxLevelOrder
                     )
                 )
             )
           GROUP BY p.status
           """)
    List<Object[]> countAccessibleByStatus(
            @Param("organizationId") UUID organizationId,
            @Param("maxLevelOrder") Integer maxLevelOrder);

    @Query("""
           SELECT p.year, COUNT(p) FROM Project p
           WHERE p.isLatestVersion = true
             AND p.discardedAt IS NULL
             AND (:organizationId IS NULL OR p.organizationId = :organizationId)
             AND (
                 p.confidentialityLevelId IS NULL
                 OR (
                     :maxLevelOrder IS NOT NULL
                     AND EXISTS (
                         SELECT c.id FROM ConfidentialityLevel c
                         WHERE c.id = p.confidentialityLevelId
                           AND c.levelOrder <= :maxLevelOrder
                     )
                 )
             )
           GROUP BY p.year
           ORDER BY p.year DESC
           """)
    List<Object[]> countAccessibleByYear(
            @Param("organizationId") UUID organizationId,
            @Param("maxLevelOrder") Integer maxLevelOrder);

    @Query("""
           SELECT p.sourceType, COUNT(p) FROM Project p
           WHERE p.isLatestVersion = true
             AND p.discardedAt IS NULL
             AND (:organizationId IS NULL OR p.organizationId = :organizationId)
             AND (
                 p.confidentialityLevelId IS NULL
                 OR (
                     :maxLevelOrder IS NOT NULL
                     AND EXISTS (
                         SELECT c.id FROM ConfidentialityLevel c
                         WHERE c.id = p.confidentialityLevelId
                           AND c.levelOrder <= :maxLevelOrder
                     )
                 )
             )
           GROUP BY p.sourceType
           """)
    List<Object[]> countAccessibleBySource(
            @Param("organizationId") UUID organizationId,
            @Param("maxLevelOrder") Integer maxLevelOrder);

    @Query("SELECT COUNT(p) FROM Project p WHERE p.organizationId = :organizationId AND p.discardedAt IS NULL AND p.isLatestVersion = true")
    long countByOrganizationId(@Param("organizationId") UUID organizationId);

    @Query("""
           SELECT COUNT(p) FROM Project p
           WHERE p.organizationId = :organizationId
             AND p.discardedAt IS NULL
             AND p.isLatestVersion = true
             AND p.status <> com.centralArchiveSystem.Archive.project.project.Project.ProjectStatus.ARCHIVED
           """)
    long countActiveProjectsByOrganizationId(@Param("organizationId") UUID organizationId);

    @Query("SELECT COUNT(p) FROM Project p WHERE p.organizationId = :organizationId AND p.status = :status AND p.discardedAt IS NULL AND p.isLatestVersion = true")
    long countByOrganizationIdAndStatus(
            @Param("organizationId") UUID organizationId,
            @Param("status") Project.ProjectStatus status);

    @Query("SELECT p.year, COUNT(p) FROM Project p WHERE p.organizationId = :organizationId AND p.discardedAt IS NULL AND p.isLatestVersion = true GROUP BY p.year ORDER BY p.year DESC")
    List<Object[]> countByYearForOrganization(@Param("organizationId") UUID organizationId);

    @Query("SELECT p FROM Project p WHERE p.projectNumber = :projectNumber AND p.organizationId = :organizationId AND p.year = :year AND p.discardedAt IS NULL AND p.isLatestVersion = true")
    Optional<Project> findActiveByProjectNumberAndOrganizationIdAndYear(
            @Param("projectNumber") String projectNumber,
            @Param("organizationId") UUID organizationId,
            @Param("year") Integer year);

    @Query("SELECT p FROM Project p WHERE p.projectNumber = :projectNumber AND p.organizationId = :organizationId AND p.year = :year")
    List<Project> findAllVersionsByProjectNumberAndOrganizationIdAndYear(
            @Param("projectNumber") String projectNumber,
            @Param("organizationId") UUID organizationId,
            @Param("year") Integer year);

    @Query("SELECT COUNT(p) FROM Project p WHERE p.confidentialityLevelId = :confidentialityLevelId")
    long countByConfidentialityLevelId(@Param("confidentialityLevelId") UUID confidentialityLevelId);

    @Query("""
           SELECT COUNT(p) FROM Project p
           WHERE p.projectTypeId = :projectTypeId
             AND p.discardedAt IS NULL
             AND p.isLatestVersion = true
           """)
    long countLatestByProjectTypeId(@Param("projectTypeId") UUID projectTypeId);

    /**
     * Feeds the incomplete-data report. Only the latest version is listed so a re-migrated project
     * is not counted once per version it went through.
     */
    List<Project> findByDataCompletionStatusAndIsLatestVersionTrueAndDiscardedAtIsNullOrderByCreatedAtDesc(
            Project.DataCompletionStatus status);
}
