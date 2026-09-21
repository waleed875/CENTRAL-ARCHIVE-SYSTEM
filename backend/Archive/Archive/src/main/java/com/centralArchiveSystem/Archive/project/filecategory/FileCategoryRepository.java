package com.centralArchiveSystem.Archive.project.filecategory;

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
public interface FileCategoryRepository extends JpaRepository<FileCategory, UUID> {

    List<FileCategory> findByIsActive(Boolean isActive);

    Optional<FileCategory> findByCode(String code);

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, UUID id);

    @Query("""
           SELECT fc FROM FileCategory fc
           WHERE (:query IS NULL OR :query = ''
                  OR LOWER(fc.name) LIKE LOWER(CONCAT('%', :query, '%'))
                  OR LOWER(fc.code) LIKE LOWER(CONCAT('%', :query, '%')))
             AND (:isActive IS NULL OR fc.isActive = :isActive)
           """)
    Page<FileCategory> searchFiltered(
            @Param("query") String query,
            @Param("isActive") Boolean isActive,
            Pageable pageable);

    @Query("SELECT fc FROM FileCategory fc WHERE " +
           "LOWER(fc.name) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(fc.code) LIKE LOWER(CONCAT('%', :query, '%'))")
    List<FileCategory> searchByNameOrCode(@Param("query") String query);

    @Query("SELECT COUNT(f) FROM com.centralArchiveSystem.Archive.project.file.FileEntity f WHERE f.categoryId = :categoryId")
    long countFilesByCategoryId(@Param("categoryId") UUID categoryId);

    @Query("""
           SELECT COUNT(f) FROM com.centralArchiveSystem.Archive.project.file.FileEntity f
           WHERE f.categoryId = :categoryId
             AND UPPER(f.status) = 'ACTIVE'
           """)
    long countActiveFilesByCategoryId(@Param("categoryId") UUID categoryId);
}
