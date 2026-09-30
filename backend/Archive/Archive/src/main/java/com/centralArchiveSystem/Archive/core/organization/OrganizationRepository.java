package com.centralArchiveSystem.Archive.core.organization;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    List<Organization> findByParentId(UUID parentId);

    List<Organization> findByType(Organization.OrganizationType type);

    List<Organization> findByIsActive(Boolean isActive);

    @Query("""
           SELECT o FROM Organization o
           WHERE (:query IS NULL OR :query = ''
                  OR LOWER(o.name) LIKE LOWER(CONCAT('%', :query, '%'))
                  OR LOWER(o.code) LIKE LOWER(CONCAT('%', :query, '%')))
             AND (:parentId IS NULL OR o.parentId = :parentId)
             AND (:type IS NULL OR o.type = :type)
             AND (:isActive IS NULL OR o.isActive = :isActive)
           """)
    Page<Organization> searchFiltered(
            @Param("query") String query,
            @Param("parentId") UUID parentId,
            @Param("type") Organization.OrganizationType type,
            @Param("isActive") Boolean isActive,
            Pageable pageable);

    @Query("SELECT o FROM Organization o WHERE " +
           "LOWER(o.name) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(o.code) LIKE LOWER(CONCAT('%', :query, '%'))")
    List<Organization> searchByNameOrCode(@Param("query") String query);

    /**
     * The rows a form offers as choices, ordered the way a person reads them.
     *
     * <p>A null {@code parentId} means "at every level" rather than "at the root", because a
     * selection list is normally fed the whole table and filtered per row, while a cascading list
     * asks for one level at a time. A derived query cannot express either of those two readings
     * without four method variants, so the null-tolerant form is spelled out here.
     */
    @Query("""
           SELECT o FROM Organization o
           WHERE (:parentId IS NULL OR o.parentId = :parentId)
             AND (:isActive IS NULL OR o.isActive = :isActive)
           ORDER BY o.name ASC
           """)
    List<Organization> findAllForSelection(
            @Param("parentId") UUID parentId,
            @Param("isActive") Boolean isActive);

    long countByParentId(UUID parentId);

    boolean existsByCodeAndIdNot(String code, UUID id);

    boolean existsByCode(String code);

    java.util.Optional<Organization> findByCode(String code);
}
