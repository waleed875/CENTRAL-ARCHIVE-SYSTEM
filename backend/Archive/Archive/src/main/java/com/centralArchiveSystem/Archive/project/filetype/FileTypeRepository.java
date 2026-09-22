package com.centralArchiveSystem.Archive.project.filetype;

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
public interface FileTypeRepository extends JpaRepository<FileType, UUID> {

    List<FileType> findByCategoryId(UUID categoryId);

    List<FileType> findByIsActive(Boolean isActive);

    List<FileType> findByCategoryIdAndIsActive(UUID categoryId, Boolean isActive);

    Optional<FileType> findByCode(String code);

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, UUID id);

    @Query("""
           SELECT ft FROM FileType ft
           WHERE (:query IS NULL OR :query = ''
                  OR LOWER(ft.name) LIKE LOWER(CONCAT('%', :query, '%'))
                  OR LOWER(ft.code) LIKE LOWER(CONCAT('%', :query, '%')))
             AND (:categoryId IS NULL OR ft.categoryId = :categoryId)
             AND (:isActive IS NULL OR ft.isActive = :isActive)
           """)
    Page<FileType> searchFiltered(
            @Param("query") String query,
            @Param("categoryId") UUID categoryId,
            @Param("isActive") Boolean isActive,
            Pageable pageable);

    @Query("SELECT ft FROM FileType ft WHERE " +
           "LOWER(ft.name) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(ft.code) LIKE LOWER(CONCAT('%', :query, '%'))")
    List<FileType> searchByNameOrCode(@Param("query") String query);

    @Query("SELECT COUNT(f) FROM com.centralArchiveSystem.Archive.project.file.FileEntity f WHERE f.typeId = :typeId")
    long countFilesByTypeId(@Param("typeId") UUID typeId);

    @Query("""
           SELECT COUNT(f) FROM com.centralArchiveSystem.Archive.project.file.FileEntity f
           WHERE f.typeId = :typeId
             AND UPPER(f.status) = 'ACTIVE'
           """)
    long countActiveFilesByTypeId(@Param("typeId") UUID typeId);
}
