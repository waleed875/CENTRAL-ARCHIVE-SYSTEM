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

    long countByCategoryIdAndDeletedAtIsNull(UUID categoryId);

    long countByTypeIdAndDeletedAtIsNull(UUID typeId);

    boolean existsByProjectIdAndChecksumSha256AndDeletedAtIsNull(
            UUID projectId,
            String checksumSha256
    );

    @Query("""
           SELECT f FROM FileEntity f
           WHERE f.projectId = :projectId
             AND f.deletedAt IS NULL
             AND (:categoryId IS NULL OR f.categoryId = :categoryId)
             AND (:typeId IS NULL OR f.typeId = :typeId)
             AND (
                  :search IS NULL OR :search = ''
                  OR LOWER(f.originalName) LIKE LOWER(CONCAT('%', :search, '%'))
                  OR LOWER(f.extension) LIKE LOWER(CONCAT('%', :search, '%'))
             )
             AND (
                  f.confidentialityLevelId IS NULL
                  OR (
                      :maxLevelOrder IS NOT NULL
                      AND EXISTS (
                          SELECT c.id
                          FROM ConfidentialityLevel c
                          WHERE c.id = f.confidentialityLevelId
                            AND c.levelOrder <= :maxLevelOrder
                      )
                  )
             )
           """)
    Page<FileEntity> searchAccessibleProjectFiles(
            @Param("projectId") UUID projectId,
            @Param("categoryId") UUID categoryId,
            @Param("typeId") UUID typeId,
            @Param("search") String search,
            @Param("maxLevelOrder") Integer maxLevelOrder,
            Pageable pageable);

    List<FileEntity> findByDeletedAtIsNotNull();

    long countByConfidentialityLevelIdAndDeletedAtIsNull(UUID confidentialityLevelId);
}
