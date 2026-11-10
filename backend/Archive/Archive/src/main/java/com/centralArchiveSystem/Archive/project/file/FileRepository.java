package com.centralArchiveSystem.Archive.project.file;

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

    List<FileEntity> findByDeletedAtIsNotNull();
    long countByConfidentialityLevelIdAndDeletedAtIsNull(UUID confidentialityLevelId);
    long countByConfidentialityLevelId(UUID confidentialityLevelId);
}
