package com.centralArchiveSystem.Archive.project.file;

import com.centralArchiveSystem.Archive.core.organization.Organization;
import com.centralArchiveSystem.Archive.project.filecategory.FileCategory;
import com.centralArchiveSystem.Archive.project.project.Project;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface FileRepository extends JpaRepository<FileEntity, UUID> {
    Page<FileEntity> findByProjectIdAndDeletedAtIsNull(UUID projectId, Pageable pageable);
    List<FileEntity> findByProjectIdAndDeletedAtIsNull(UUID projectId);
    Page<FileEntity> findByProjectId(UUID projectId, Pageable pageable);
    List<FileEntity> findByProjectId(UUID projectId);
    List<FileEntity> findByCategoryId(UUID categoryId);
    List<FileEntity> findByTypeId(UUID typeId);
    long countByProjectIdAndDeletedAtIsNull(UUID projectId);
    /** Includes soft-deleted history so draft deletion cannot erase archived file records. */
    long countByProjectId(UUID projectId);
    long countByCategoryIdAndDeletedAtIsNull(UUID categoryId);
    long countByTypeIdAndDeletedAtIsNull(UUID typeId);
    boolean existsByProjectIdAndChecksumSha256AndDeletedAtIsNull(UUID projectId, String checksumSha256);

    String FILE_CLEARANCE = """
        (f.confidentialityLevelId IS NULL OR (:maxLevelOrder IS NOT NULL AND EXISTS (
          SELECT c.id FROM ConfidentialityLevel c
          WHERE c.id = f.confidentialityLevelId AND c.levelOrder <= :maxLevelOrder)))
        """;
    String PROJECT_CLEARANCE = """
        EXISTS (SELECT p.id FROM Project p WHERE p.id=f.projectId
          AND (p.confidentialityLevelId IS NULL OR (:maxLevelOrder IS NOT NULL AND EXISTS (
              SELECT pc.id FROM ConfidentialityLevel pc
              WHERE pc.id=p.confidentialityLevelId AND pc.levelOrder <= :maxLevelOrder))))
        """;

    @Query("""
        SELECT f FROM FileEntity f WHERE f.projectId=:projectId
          AND ((UPPER(:status)='DELETED' AND f.deletedAt IS NOT NULL)
               OR ((:status IS NULL OR UPPER(:status)<>'DELETED') AND f.deletedAt IS NULL))
          AND (:categoryId IS NULL OR f.categoryId=:categoryId OR :categoryId MEMBER OF f.categoryIds)
          AND (:typeId IS NULL OR f.typeId=:typeId)
          AND (:status IS NULL OR UPPER(f.status)=UPPER(:status))
          AND (:search IS NULL OR :search='' OR
               LOWER(f.originalName) LIKE LOWER(CONCAT('%',:search,'%')) OR
               LOWER(f.extension) LIKE LOWER(CONCAT('%',:search,'%')))
          AND
        """ + FILE_CLEARANCE)
    Page<FileEntity> searchAccessibleProjectFiles(
            @Param("projectId") UUID projectId, @Param("categoryId") UUID categoryId,
            @Param("typeId") UUID typeId, @Param("status") String status, @Param("search") String search,
            @Param("maxLevelOrder") Integer maxLevelOrder, Pageable pageable);

    @Query("SELECT COUNT(f) FROM FileEntity f WHERE f.deletedAt IS NULL AND " + FILE_CLEARANCE + " AND " + PROJECT_CLEARANCE)
    long countAccessibleActive(@Param("maxLevelOrder") Integer maxLevelOrder);

    @Query("SELECT COALESCE(SUM(f.sizeBytes),0) FROM FileEntity f WHERE f.deletedAt IS NULL AND " + FILE_CLEARANCE + " AND " + PROJECT_CLEARANCE)
    long sumAccessibleActiveSize(@Param("maxLevelOrder") Integer maxLevelOrder);

    @Query("SELECT f FROM FileEntity f WHERE f.deletedAt IS NULL AND " + FILE_CLEARANCE + " AND " + PROJECT_CLEARANCE)
    Page<FileEntity> findAccessibleRecent(@Param("maxLevelOrder") Integer maxLevelOrder, Pageable pageable);

    /** Batch count of files actually visible to the caller, keyed by project ID. */
    @Query("""
        SELECT f.projectId, COUNT(f) FROM FileEntity f
        WHERE f.projectId IN :projectIds
          AND f.deletedAt IS NULL
          AND UPPER(f.status) = 'ACTIVE'
          AND """ + FILE_CLEARANCE + """
        GROUP BY f.projectId
        """)
    List<Object[]> countAccessibleActiveByProjectIds(
            @Param("projectIds") java.util.Collection<UUID> projectIds,
            @Param("maxLevelOrder") Integer maxLevelOrder);

    /**
     * Global filename search: never matches project names. Clearance and active
     * project/file predicates are applied in the database before both page and
     * total count are computed. Escaped LIKE uses '!' as its escape character.
     */
    String GLOBAL_FILE_WHERE = """
        FROM FileEntity f
        JOIN Project p ON p.id = f.projectId
        JOIN Organization o ON o.id = p.organizationId
        LEFT JOIN FileCategory c ON c.id = f.categoryId
        WHERE f.deletedAt IS NULL AND UPPER(f.status) = 'ACTIVE'
          AND p.discardedAt IS NULL
          AND (:includeVersions = true OR p.isLatestVersion = true)
          AND (:namePattern IS NULL OR LOWER(f.originalName) LIKE :namePattern ESCAPE '!')
          AND (:extension IS NULL OR LOWER(f.extension) = :extension
               OR LOWER(f.originalName) LIKE :extensionPattern ESCAPE '!')
          AND (:categoryId IS NULL OR f.categoryId = :categoryId OR :categoryId MEMBER OF f.categoryIds)
          AND (:organizationId IS NULL OR p.organizationId = :organizationId)
          AND (:projectNumber IS NULL OR LOWER(p.projectNumber) LIKE :projectNumber ESCAPE '!')
          AND (:projectYear IS NULL OR p.year = :projectYear)
          AND f.createdAt >= :startDate
          AND f.createdAt < :endDateExclusive
          AND """ + FILE_CLEARANCE + " AND " + PROJECT_CLEARANCE;

    @Query(value = """
        SELECT new com.centralArchiveSystem.Archive.project.file.dto.GlobalFileSearchResult(
          f.id, f.projectId, f.originalName, f.extension, f.mimeType, f.sizeBytes,
          f.createdAt, f.categoryId, c.name, p.name, p.projectNumber, p.year,
          p.organizationId, o.name, p.versionNo, p.isLatestVersion)
        """ + GLOBAL_FILE_WHERE + """
        ORDER BY
          CASE WHEN :sortMode = 'name' THEN LOWER(f.originalName) ELSE NULL END ASC,
          CASE WHEN :sortMode = 'oldest' THEN f.createdAt ELSE NULL END ASC,
          CASE WHEN :sortMode = 'newest' THEN f.createdAt ELSE NULL END DESC,
          f.id ASC
        """,
        countQuery = "SELECT COUNT(f.id) " + GLOBAL_FILE_WHERE)
    Page<com.centralArchiveSystem.Archive.project.file.dto.GlobalFileSearchResult> searchGlobalFileNames(
            @Param("namePattern") String namePattern,
            @Param("extension") String extension,
            @Param("extensionPattern") String extensionPattern,
            @Param("categoryId") UUID categoryId,
            @Param("organizationId") UUID organizationId,
            @Param("projectNumber") String projectNumber,
            @Param("projectYear") Integer projectYear,
            @Param("startDate") java.time.LocalDateTime startDate,
            @Param("endDateExclusive") java.time.LocalDateTime endDateExclusive,
            @Param("includeVersions") boolean includeVersions,
            @Param("maxLevelOrder") Integer maxLevelOrder,
            @Param("sortMode") String sortMode,
            Pageable pageable);

    List<FileEntity> findByDeletedAtIsNotNull();
    long countByConfidentialityLevelIdAndDeletedAtIsNull(UUID confidentialityLevelId);
    long countByConfidentialityLevelId(UUID confidentialityLevelId);
}
