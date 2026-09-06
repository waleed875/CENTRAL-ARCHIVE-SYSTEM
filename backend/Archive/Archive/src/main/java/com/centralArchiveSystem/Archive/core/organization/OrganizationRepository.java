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

    @Query("SELECT o FROM Organization o WHERE " +
           "LOWER(o.name) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(o.code) LIKE LOWER(CONCAT('%', :query, '%'))")
    Page<Organization> searchByNameOrCode(@Param("query") String query, Pageable pageable);

    @Query("SELECT o FROM Organization o WHERE " +
           "LOWER(o.name) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(o.code) LIKE LOWER(CONCAT('%', :query, '%'))")
    List<Organization> searchByNameOrCode(@Param("query") String query);

    long countByParentId(UUID parentId);

    boolean existsByCodeAndIdNot(String code, UUID id);

    boolean existsByCode(String code);
}
