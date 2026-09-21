package com.centralArchiveSystem.Archive.project.project;

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

    @Query("SELECT COALESCE(MAX(p.versionNo), 0) FROM Project p WHERE p.projectUid = :projectUid")
    int findMaxVersionNoByProjectUid(@Param("projectUid") UUID projectUid);

    List<Project> findByOrganizationIdAndYear(UUID organizationId, Integer year);

    List<Project> findByStatus(Project.ProjectStatus status);

    List<Project> findByIsLatestVersion(Boolean isLatestVersion);

    Optional<Project> findByProjectNumberAndOrganizationIdAndYear(
            String projectNumber, UUID organizationId, Integer year);

    @Query("""
           SELECT p FROM Project p
           WHERE (:searchTerm IS NULL OR :searchTerm = '' OR
                  LOWER(p.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
                  LOWER(p.projectNumber) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
                  LOWER(p.projectCode) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
                  LOWER(p.description) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
                  LOWER(p.keywords) LIKE LOWER(CONCAT('%', :searchTerm, '%')))
             AND (:organizationId IS NULL OR p.organizationId = :organizationId)
             AND (:status IS NULL OR p.status = :status)
             AND (:year IS NULL OR p.year = :year)
             AND p.isLatestVersion = true
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
            @Param("maxLevelOrder") Integer maxLevelOrder,
            Pageable pageable);

    @Query("""
           SELECT p FROM Project p
           WHERE p.projectUid = :projectUid
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

    @Query("SELECT COUNT(p) FROM Project p WHERE p.organizationId = :organizationId AND p.isLatestVersion = true")
    long countByOrganizationId(@Param("organizationId") UUID organizationId);

    @Query("SELECT COUNT(p) FROM Project p WHERE p.organizationId = :organizationId AND p.status = :status AND p.isLatestVersion = true")
    long countByOrganizationIdAndStatus(
            @Param("organizationId") UUID organizationId,
            @Param("status") Project.ProjectStatus status);

    @Query("SELECT p.year, COUNT(p) FROM Project p WHERE p.organizationId = :organizationId AND p.isLatestVersion = true GROUP BY p.year ORDER BY p.year DESC")
    List<Object[]> countByYearForOrganization(@Param("organizationId") UUID organizationId);

    @Query("SELECT p FROM Project p WHERE p.projectNumber = :projectNumber AND p.organizationId = :organizationId AND p.year = :year AND p.isLatestVersion = true")
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
}
