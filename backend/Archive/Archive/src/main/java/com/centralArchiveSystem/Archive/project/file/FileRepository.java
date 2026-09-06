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

    @Query("SELECT f FROM FileEntity f WHERE f.projectId = :projectId AND f.deletedAt IS NULL AND " +
           "(LOWER(f.originalName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(f.extension) LIKE LOWER(CONCAT('%', :query, '%')))")
    Page<FileEntity> searchByProjectAndQuery(@Param("projectId") UUID projectId, @Param("query") String query, Pageable pageable);

    @Query("SELECT f FROM FileEntity f WHERE f.projectId = :projectId AND f.deletedAt IS NULL AND " +
           "f.categoryId = :categoryId")
    Page<FileEntity> findByProjectAndCategory(@Param("projectId") UUID projectId, @Param("categoryId") UUID categoryId, Pageable pageable);

    @Query("SELECT f FROM FileEntity f WHERE f.projectId = :projectId AND f.deletedAt IS NULL AND " +
           "f.typeId = :typeId")
    Page<FileEntity> findByProjectAndType(@Param("projectId") UUID projectId, @Param("typeId") UUID typeId, Pageable pageable);

    List<FileEntity> findByDeletedAtIsNotNull();

    long countByConfidentialityLevelIdAndDeletedAtIsNull(UUID confidentialityLevelId);
}
